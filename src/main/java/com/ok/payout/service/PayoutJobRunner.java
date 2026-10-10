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
    // 현재 기록의존성 없음. 해당 부분 문의 필요. 일단은 형만 처리
    // TODO 의논필요. 배치 기록 관련해서.
    public JobExecution run() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addLocalDateTime("requestedAt", LocalDateTime.now())
                .toJobParameters();
        return jobOperator.start(payoutJob, params);
    }
}
