package com.ok.payout.service;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class PayoutJobRunner {

    private final JobOperator jobOperator;
    private final Job payoutJob;

    // 현재 시각 기준 (전역 ClockConfig, JVM 시간대 = Asia/Seoul)
    // requestedAt의 날짜가 곧 정산 실행일(runDate)이 되어 정산 범위를 정하므로, 실행 환경과 상관없이 같은 시간대여야 함
    // 예) 스케줄러는 02:00 KST에 실행되는데 now()가 UTC(전날 17:00)를 주면 실행일이 하루 앞당겨져 다른 후보가 정산됨 (#93)
    private final Clock clock;

    // 정산 Job 1회 실행. 요청 시각을 파라미터로 넣어 실행마다 새 JobInstance로 기록되게 함
    // 실패한 Job은 이어서 재시작(restart)하지 않고 새로 재실행함
    // 미정산 조건(정산 항목에 없는 후보)으로 이미 정산된 후보는 다시 고르지 않으므로 재실행해도 이중 정산되지 않음
    // (이 조건은 snapshotStep이 스냅샷 대상을 고를 때 적용됨. 재실행하면 새 스냅샷이 만들어지고, 이전 실행의 draft는 snapshot_id가 달라 섞이지 않음)
    public JobExecution run() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addLocalDateTime("requestedAt", LocalDateTime.now(clock))
                .toJobParameters();
        return jobOperator.start(payoutJob, params);
    }
}
