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

    // 매일 오전 11시(한국 시간) 정산 Job 실행. 결과 상태를 로그로 남김 (실패 원인은 Spring Batch가 로그로 남김, 알림은 #52)
    // 배포 서버가 9~18시에만 켜져 새벽 2시에서 옮김. 켜진 직후는 피하고 대사(12:00)보다 앞서도록 11시로 정함 (#650)
    // 정산 범위는 실행 날짜로 정해서(PayoutJobRunner) 같은 날 안에서 시각을 옮겨도 정산되는 후보는 같음
    @Scheduled(cron = "0 0 11 * * *", zone = "Asia/Seoul")
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
