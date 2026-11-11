package com.ok.payout.adapter.in.batch;

import com.ok.common.exception.RestApiException;
import com.ok.payout.exception.PayoutErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutJobScheduler {

    private final PayoutJobRunner payoutJobRunner;

    // 매일 새벽 2시(한국 시간) 정산 Job 실행. 결과 상태를 로그로 남김 (실패 원인은 Spring Batch가 로그로 남김, 알림은 #52)
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public void runDaily() throws Exception {
        JobExecution execution;
        try {
            execution = payoutJobRunner.runScheduled();
        } catch (RestApiException e) {
            // 관리자가 같은 시각에 돌리는 중이면 정상이므로 오류 없이 건너뜀. 다른 예외는 그대로 위로 보냄
            if (e.getErrorCode() == PayoutErrorCode.PAYOUT_JOB_ALREADY_RUNNING) {
                log.warn("정산 Job이 이미 실행 중이라 이번 정기 실행을 건너뜀");
                return;
            }
            throw e;
        }
        log.info("정산 Job 종료 executionId={}, status={}", execution.getId(), execution.getStatus());
    }
}
