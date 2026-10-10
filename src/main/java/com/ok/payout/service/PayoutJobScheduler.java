package com.ok.payout.service;

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
        JobExecution execution = payoutJobRunner.run();
        log.info("정산 Job 종료 executionId={}, status={}", execution.getId(), execution.getStatus());
    }
}
