package com.ok.payout.adapter.in.batch;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.ok.payout.application.port.in.PayoutPublishResult;
import com.ok.payout.application.port.in.PayoutUseCase;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

/**
 * outbox 발송을 잠금(payoutOutboxSend)을 잡은 채로 한다 (#620).
 * 정산 Job 발행 단계와 짧은 간격 재발송이 이 클래스를 같이 써서, 서버가 여러 대여도 같은 줄을 동시에 보내지 않는다.
 * 잠금을 못 잡으면 기다리지 않고 빈 값을 돌려준다. 다른 쪽이 지금 PENDING 줄을 보내고 있고, 그쪽이 못 본 줄은 다음 재발송이 보내서다.
 */
@Component
@RequiredArgsConstructor
public class PayoutOutboxSendLock {

    // 정산 Job 잠금(payoutJob)과 이름이 달라 정산 Job 전체를 막지 않음. 실제로 겹치는 곳은 발행 단계뿐이라서임 (#620 설계 결정 2)
    // 줄은 ShedLock이 처음 잠글 때 shedlock 표에 스스로 넣음 (마이그레이션 필요 없음)
    static final String LOCK_NAME = "payoutOutboxSend";
    // 연속 실패면 멈추므로 보통 몇 초~몇 분이면 끝남. 서버가 죽어 잠금이 남아도 이 시간 뒤 풀림
    // 더 오래 걸려도 실행 중에는 자동 연장되어 풀리지 않음 (PayoutJobLockConfig의 KeepAliveLockProvider)
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(10);
    // 끝나면 바로 풂: 재발송이 끝난 직후 정산 Job 발행 단계가 오면 잠금이 남아 있어 그날 발송을 건너뛰면 안 되므로
    // 정해진 시각 실행이 아니라 서버마다 같은 시각에 또 도는 문제(PayoutDepositCheckScheduler의 1분 유지 이유)가 없음
    private static final Duration LOCK_AT_LEAST_FOR = Duration.ZERO;

    private final PayoutUseCase payoutUseCase;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Clock clock;

    /**
     * 잠금을 잡고 outbox의 PENDING 줄을 보낸다.
     *
     * @return 보낸 결과. 다른 곳이 잠금을 잡고 있어 보내지 않았으면 빈 값
     */
    public Optional<PayoutPublishResult> publishPending() {
        LockConfiguration lockConfig = new LockConfiguration(clock.instant(), LOCK_NAME, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR);
        LockingTaskExecutor.TaskResult<PayoutPublishResult> result;
        // executeWithLock이 Throwable을 던진다고 선언돼 있어, 실행 중 난 예외(RuntimeException·Error)는 그대로 다시 던지고
        // 그 밖의 검사 예외만 IllegalStateException으로 감쌈 (PayoutDepositCheckScheduler와 같은 방식)
        try {
            result = lockingTaskExecutor.executeWithLock(payoutUseCase::publishPending, lockConfig);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        if (!result.wasExecuted()) {
            return Optional.empty();
        }
        return Optional.of(result.getResult());
    }
}
