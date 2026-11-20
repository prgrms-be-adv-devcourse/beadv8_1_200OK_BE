package com.ok.payout.adapter.in.batch;

import java.time.Clock;
import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ok.payout.application.port.in.PayoutDepositCheckRunResult;
import com.ok.payout.application.port.in.PayoutDepositCheckUseCase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

/**
 * 정산·지갑 대사를 매일 한 번 시작한다 (#619).
 * 서버가 여러 대여도 한 대만 돌게 정산 Job과 같은 ShedLock(DB 잠금)으로 잠근다. 잠금 이름은 정산 Job과 달라 서로 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutDepositCheckScheduler {

    private static final String LOCK_NAME = "payoutDepositCheck";
    // 대기 줄이 많아도 묶음마다 한 번 보내는 일이라 몇 분이면 끝남. 서버가 죽어 잠금이 남아도 이 시간 뒤 풀림
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(30);
    // 서버마다 시계가 조금 달라 한 대가 끝난 직후 다른 대가 같은 시각 실행을 또 하지 않게 잠금을 잠시 유지함
    private static final Duration LOCK_AT_LEAST_FOR = Duration.ofMinutes(1);

    private final PayoutDepositCheckUseCase payoutDepositCheckUseCase;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Clock clock;

    // 실행 시각은 설정 값으로 둠. 기본 12:00은 지갑과 합의한 시각(PR #548 문서 4-1절)
    // 정산 Job이 11:00에 시작해 12:00 전에 끝난다는 전제로 그대로 둠 (#650). 정산이 더 오래 걸리면 끝나지 않은 정산은 다음 날 물음
    @Scheduled(cron = "${payout.deposit-check.cron:0 0 12 * * *}", zone = "Asia/Seoul")
    public void runDaily() {
        LockConfiguration lockConfig = new LockConfiguration(clock.instant(), LOCK_NAME, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR);
        LockingTaskExecutor.TaskResult<PayoutDepositCheckRunResult> result;
        // executeWithLock이 Throwable을 던진다고 선언돼 있어, 실행 중 난 예외(RuntimeException·Error)는 그대로 다시 던지고
        // 그 밖의 검사 예외만 IllegalStateException으로 감쌈 (PayoutJobRunner와 비슷한 방식)
        try {
            result = lockingTaskExecutor.executeWithLock(payoutDepositCheckUseCase::runCheck, lockConfig);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        if (!result.wasExecuted()) {
            // 다른 서버가 이미 돌고 있으면 정상이라 건너뜀
            log.info("다른 서버가 대사를 돌고 있어 이번 실행을 건너뜀");
            return;
        }
        PayoutDepositCheckRunResult run = result.getResult();
        log.info("정산 대사 종료. 닫은 줄={}, 물은 정산={}, 묶음={}, 보내기 실패로 멈춤={}",
                run.closedCount(), run.askedCount(), run.batchCount(), run.stoppedBySendFailure());
    }
}
