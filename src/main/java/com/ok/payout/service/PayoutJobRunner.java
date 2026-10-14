package com.ok.payout.service;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class PayoutJobRunner {

    private final JobOperator jobOperator;
    private final Job payoutJob;

    // 정산 Job 1회 실행. 요청 시각을 파라미터로 넣어 실행마다 새 JobInstance로 기록되게 함
    // 실패한 Job은 이어서 재시작(restart)하지 않고 새로 재실행함
    // 미정산 조건(정산 항목에 없는 후보)으로 이미 정산된 후보는 다시 고르지 않으므로 재실행해도 이중 정산되지 않음
    public JobExecution run() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addLocalDateTime("requestedAt", LocalDateTime.now())
                .toJobParameters();
        return jobOperator.start(payoutJob, params);
    }
}
