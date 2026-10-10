package com.ok.payout.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PayoutJobScheduler {

    private final PayoutJobRunner payoutJobRunner;

    // 매일 새벽 2시(한국 시간) 정산 Job 실행
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public void runDaily() throws Exception {
        payoutJobRunner.run();
    }
}
