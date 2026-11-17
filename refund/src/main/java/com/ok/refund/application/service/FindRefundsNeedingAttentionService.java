package com.ok.refund.application.service;

import com.ok.refund.application.port.in.FindRefundsNeedingAttentionUseCase;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 사람이 봐야 하는 처리 중 환불을 찾아 WARN 로그로 알린다(#577). 무엇을 고를지는 영속 포트의 조회가 정한다.
 * 알림 장치(슬랙 등)가 레포에 없어 로그로 남긴다. 되돌리기(#546) 등 다른 실패 처리도 이 찾기를 다시 쓴다.
 * WARN인 이유: 환불은 계속 처리 중으로 남지만 사람이 확인해야 하는 이상이다(service.md 로그 표, ERROR는 처리되지 못한 예외).
 * 앞에 {@code [환불-확인필요]}를 붙여 사람 확인 건을 한 가지 표시로 찾게 한다(정산의 {@code [정산-...]} 접두어와 같은 방식, 멘토 2차 리뷰).
 * 정산은 같은 경우에 ERROR를 쓴다. 팀 로그 규칙에 "사람 확인 = ERROR + 접두어"가 정해지면 수준만 바꾼다(#649 C1).
 */
@Slf4j
@Service
public class FindRefundsNeedingAttentionService implements FindRefundsNeedingAttentionUseCase {

    // 지갑 결과 대기가 이만큼 오래되면 사람이 보게 한다. 결과 대기는 시간이 지나면 같은 요청을 다시 보내며 멈추지 않으므로(#577 결정),
    // 이 기준이 없으면 결과가 끝내 오지 않는 환불을 아무도 모른다. 다시 요청 간격(5분)을 여러 번 거친 뒤에 알리려고 30분으로 잡았다
    // TODO(#577): 설정 파일로 뺄지 정한다
    static final Duration AWAITING_TOO_LONG = Duration.ofMinutes(30);
    // 로그 한 줄에 적는 환불 번호 수. 많이 쌓여도 로그 한 줄이 너무 길어지지 않게 앞 몇 개만 적고 나머지는 건수로 알린다
    static final int SAMPLE_SIZE = 10;

    private final RefundStepPersistencePort refundStepPersistencePort;
    private final Clock clock;
    private final TransactionTemplate readOnlyTransaction;

    public FindRefundsNeedingAttentionService(RefundStepPersistencePort refundStepPersistencePort, Clock clock,
                                              PlatformTransactionManager transactionManager) {
        this.refundStepPersistencePort = refundStepPersistencePort;
        this.clock = clock;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    @Override
    public int findAndReport() {
        LocalDateTime awaitingSince = LocalDateTime.now(clock).minus(AWAITING_TOO_LONG);
        List<Long> refundIds = readOnlyTransaction.execute(
                status -> refundStepPersistencePort.findRefundIdsNeedingAttention(awaitingSince));
        if (refundIds.isEmpty()) {
            return 0;
        }
        List<Long> sample = refundIds.subList(0, Math.min(SAMPLE_SIZE, refundIds.size()));
        log.warn("[환불-확인필요] 사람이 봐야 하는 처리 중 환불이 있음(멈춘 단계, 단계 줄 없음, 다음 줄 없음, 지갑 결과 대기 {}분 넘음, 확인 필요). count={}, refundIds(앞 {}개)={}",
                AWAITING_TOO_LONG.toMinutes(), refundIds.size(), SAMPLE_SIZE, sample);
        return refundIds.size();
    }
}
