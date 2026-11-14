package com.ok.payout.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.payout.application.port.in.PayoutFailureResolutionUseCase;
import com.ok.payout.application.port.in.PayoutOrderResolutionResult;
import com.ok.payout.application.port.in.PayoutOrderResolveCommand;
import com.ok.payout.application.port.out.PayoutFailureResolutionRepository;
import com.ok.payout.application.port.out.PayoutQueryRepository;
import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.domain.PayoutFailureResolution;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.domain.PayoutResolutionMethod;
import com.ok.payout.exception.PayoutErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

// 잠기거나 멈춘 주문의 해결 (#568)
// 해결 = "다시 검사해도 된다"는 관리자 표시. 돈을 바로 내보내지 않고, 다음 배치가 금액 검사·결과 검증부터 다시 거침
//   그래서 데이터·코드를 고치지 않고 해결하면 새 실패 기록이 생겨 다시 잠기거나 멈춤 (되돌리기는 두지 않음: 새 실패 기록이 해결을 상쇄)
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutFailureResolutionService implements PayoutFailureResolutionUseCase {

    // MySQL 오류 번호 1062(ER_DUP_ENTRY, 중복 키). 해결 기록 표에서 중복으로 막히는 제약은 실패 기록 ID 유니크 하나뿐이라(기본 키는 자동 증가),
    // 이 번호면 "같은 실패 기록을 이미 해결함"이라는 뜻. 오류 메시지 글자는 DB 버전·언어 설정에 따라 바뀔 수 있어 번호로 봄
    private static final int MYSQL_DUPLICATE_KEY_ERROR = 1062;

    private final PayoutQueryRepository payoutQueryRepository;
    private final PayoutFailureResolutionRepository payoutFailureResolutionRepository;

    @Override
    @Transactional
    public PayoutOrderResolutionResult resolveOrder(PayoutOrderResolveCommand command) {
        Long orderId = command.orderId();
        // 해결할 것이 있는지: 스냅샷이 그 주문을 빼는 두 조건(잠김, 멈춤)과 같은 기준으로 봄
        //   잠김 = 해결 안 된 수수료 불일치·결과 검증 불일치 실패가 있음, 멈춤 = 성공한 실행에서 해결 안 된 실패가 MAX_FAILED_RUNS번 이상
        //   1~2번 실패한 주문은 다음 실행에서 저절로 다시 검사되므로 해결 대상이 아님
        // 멈춤 판정은 스냅샷과 같은 세는 SQL에 주문 조건만 더한 조회로 함: 기준이 어긋나지 않으면서 그 주문만 셈
        boolean locked = payoutQueryRepository.isOrderLocked(orderId);
        long failedRuns = payoutQueryRepository.countFailedRunsOfOrder(orderId);
        if (!locked && failedRuns < PayoutPolicy.MAX_FAILED_RUNS) {
            throw new RestApiException(PayoutErrorCode.NOTHING_TO_RESOLVE);
        }

        // 저장 대상: 해결 안 된 실패 기록 전부 (사유·실행 성공 여부·정산 여부로 거르지 않음)
        //   #549 횟수는 사유를 가리지 않고 세므로, 잠그는 사유에만 해결 기록을 달면 횟수가 남아 계속 멈춰 있음
        //   예) 초과 청구 2번 + 결과 검증 불일치 1번 = 3번 → 셋 다 해결해야 0번
        List<PayoutFailure> failures = payoutQueryRepository.findUnresolvedFailuresByOrderId(orderId);
        // 판정 뒤 다른 요청이 먼저 해결해 비었을 수 있음 (DB 격리 수준이 READ COMMITTED일 때). 같은 뜻이라 409
        if (failures.isEmpty()) {
            throw new RestApiException(PayoutErrorCode.NOTHING_TO_RESOLVE);
        }
        List<PayoutFailureResolution> resolutions = new ArrayList<>();
        for (PayoutFailure failure : failures) {
            resolutions.add(PayoutFailureResolution.of(
                    failure, PayoutResolutionMethod.REPROCESS, command.adminId(), command.memo()));
        }

        // 같은 주문을 동시에 두 번 해결하면 둘 다 위 판정을 통과한 뒤, 두 번째 저장이 실패 기록 ID 유니크에 걸림
        //   (IDENTITY라 saveAll에서 바로 INSERT가 나가 여기서 예외가 남. 실패 기록 ID 순서로 넣어 서로 기다리는 데드락 대신 유니크 위반이 남)
        //   관리자에게는 "이미 풀림"과 같은 뜻이라 409로 바꿈. 예외를 다시 던져야 이 트랜잭션이 롤백되고 첫 번째 해결만 남음
        //   (잡고 정상 반환하면 이미 롤백 표시된 트랜잭션을 커밋하려다 500이 남)
        List<PayoutFailureResolution> saved;
        try {
            saved = payoutFailureResolutionRepository.saveAll(resolutions);
        } catch (DataIntegrityViolationException e) {
            // 중복 키(유니크 위반)일 때만 409: FK(1452)·NOT NULL·길이 위반 같은 코드 버그까지 "해결할 것 없음"으로 가리지 않으려고
            if (isDuplicateResolution(e)) {
                throw new RestApiException(PayoutErrorCode.NOTHING_TO_RESOLVE);
            }
            throw e;
        }

        PayoutFailureResolution first = saved.get(0);
        // 누가 어떤 주문을 다시 처리했는지는 정산에 영향을 주는 관리자 조치라 INFO로 남김 (메모는 길이를 예측할 수 없어 빼고 해결 기록에만 둠)
        log.info("정산 실패 해결. orderId={}, adminId={}, 해결한 실패 기록 수={}", orderId, command.adminId(), saved.size());
        return new PayoutOrderResolutionResult(orderId, saved.size(), first.getMethod().name(),
                first.getAdminId(), first.getCreatedAt(), first.getMemo());
    }

    // 원인 사슬에서 DB 드라이버 예외(SQLException)를 찾아 오류 번호가 중복 키(1062)인지 봄 (통합 테스트에서 실제 MySQL로 확인)
    private static boolean isDuplicateResolution(DataIntegrityViolationException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getErrorCode() == MYSQL_DUPLICATE_KEY_ERROR;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
