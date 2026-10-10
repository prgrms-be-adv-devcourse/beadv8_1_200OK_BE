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

    // 정산 Job 1회 실행. 요청 시각을 파라미터로 넣어 매번 새 실행으로 기록되게 함
    // TODO(#52): 실행 기록은 현재 메모리 저장 (batch-jdbc 미적용), DB 저장 여부 논의 필요
    public JobExecution run() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addLocalDateTime("requestedAt", LocalDateTime.now())
                .toJobParameters();
        return jobOperator.start(payoutJob, params);
    }
}
