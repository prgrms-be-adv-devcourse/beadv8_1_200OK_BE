package com.ok.payout.service;

import com.ok.payout.domain.PayoutPolicy;
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

    // 대기 건너뛰기 실행 때 실행 기준 시각을 미래로 미는 일수 (#224)
    // 상한 = 실행일 − 대기 일수 00:00 이므로, 대기 일수 + 1이면 상한이 내일 00:00이 되어 오늘 생긴 후보까지 들어옴
    // 자정 직전에 실행해도 그 사이 생긴 후보가 빠지지 않도록 하루 더 여유를 둠 (대기 일수 + 2 = 17일)
    private static final int SKIP_WAIT_SHIFT_DAYS = PayoutPolicy.SETTLEMENT_WAITING_DAYS + 2;

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
        return start(LocalDateTime.now(clock));
    }

    // 대기 기간(15일)을 기다리지 않고 지금 있는 미정산 후보를 정산함 (발표·연동 테스트용, #224)
    // 정산 Job은 requestedAt의 날짜를 "오늘"로 보고 상한을 계산하므로, 이 값만 미래로 넣어 대기를 건너뜀
    // 서버 시계, 정산 결과의 생성 시각, 다음 정산의 하한(마지막 정산 생성 시각 기준)은 실제 시각을 그대로 씀
    // 스냅샷의 조회 상한(periodTo)도 미래 날짜로 기록되며, 평소 실행과 구분하는 값은 따로 남기지 않음
    // 이미 정산된 후보는 미정산 조건으로 걸러지므로 여러 번 실행해도 이중 정산되지 않음
    public JobExecution runSkippingWait() throws Exception {
        return start(LocalDateTime.now(clock).plusDays(SKIP_WAIT_SHIFT_DAYS));
    }

    private JobExecution start(LocalDateTime requestedAt) throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addLocalDateTime("requestedAt", requestedAt)
                .toJobParameters();
        return jobOperator.start(payoutJob, params);
    }
}
