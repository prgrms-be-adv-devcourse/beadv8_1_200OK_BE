package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.in.RefundSagaUseCase;
import com.ok.refund.application.port.in.ResumeDueRefundsUseCase;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 다시 볼 시각이 지난 승인 뒤 단계가 있는 환불을 찾아 지휘자의 환불 번호 입구({@link RefundSagaUseCase#resume})로 이어 간다(#576).
 * 무엇을 다시 할지, 실제로 다시 집을지는 지휘자가 환불 행을 잠근 뒤 정한다. 여기서는 찾고 부르기만 한다.
 * 트랜잭션을 열지 않는다. 지휘자 입구는 트랜잭션 밖에서만 불러야 해서다(안에서 부르면 실패 기록이 함께 롤백된다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeDueRefundsService implements ResumeDueRefundsUseCase {

    // 한 번에 집는 환불 수. 알람이 집는 것은 실패해서 멈춘 환불뿐이라 보통 한 번에 몇 건이다.
    // 많이 쌓여도 한 회차가 너무 길어지지 않게 묶음으로 끊고, 남은 것은 다음 회차가 이어 간다.
    // TODO(#577): 설정 파일로 뺄지 정한다. 지금은 실패한 환불만 집는 양을 보고 정한 상수다
    static final int BATCH_SIZE = 50;

    private final RefundStepPersistencePort refundStepPersistencePort;
    private final RefundSagaUseCase refundSagaUseCase;
    private final Clock clock;

    // 환불마다 예외를 잡고 다음 환불로 넘어간다(#575 결정 16). 지휘자 입구는 예외를 그대로 올리므로, 잡지 않으면 환불 하나 때문에 그 회차 전체가 멈춘다.
    // 실패한 단계는 지휘자가 이미 단계 줄에 기록했으므로(다음 시도 시각 또는 멈춤) 여기서는 남기기만 한다.
    // 다른 모듈 실패(RestApiException, *_API_FAILED 등)는 단계 줄에 남아 다음 회차가 다시 시도하거나 멈추므로 WARN,
    // 그 밖의 예외는 코드 버그일 수 있어 사람이 봐야 하므로 ERROR다
    @Override
    public int resumeDue() {
        List<Long> refundIds = refundStepPersistencePort.findRefundIdsDueForResume(LocalDateTime.now(clock), BATCH_SIZE);
        for (Long refundId : refundIds) {
            try {
                refundSagaUseCase.resume(refundId);
            } catch (RestApiException e) {
                log.warn("다시 진행 알람에서 다른 모듈 호출이 실패해 다음 회차로 넘김. refundId={}, errorCode={}",
                        refundId, e.getErrorCode().name());
            } catch (RuntimeException e) {
                log.error("다시 진행 알람에서 환불을 이어 가지 못함. 다음 환불로 넘어감. refundId={}", refundId, e);
            }
        }
        return refundIds.size();
    }
}
