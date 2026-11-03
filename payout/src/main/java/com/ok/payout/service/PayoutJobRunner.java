package com.ok.payout.service;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.domain.PayoutRunType;
import com.ok.payout.exception.PayoutErrorCode;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

// 정산 Job을 실행하는 유일한 입구. 경로(스케줄러·관리자 실행·대기 건너뛰기)마다 메서드를 나눠 실행 유형을 여기서 정함 (#389)
// 컨트롤러는 domain 타입(PayoutRunType)을 쓸 수 없어(api.md) 실행 유형을 인자로 받지 않고 메서드로 나눔
@Component
public class PayoutJobRunner {

    // Job 파라미터 이름. 필수 파라미터 검사기(PayoutJobConfig.payoutJob)와 스냅샷 Tasklet이 같은 이름을 씀
    public static final String REQUESTED_AT_KEY = "requestedAt";
    public static final String RUN_TYPE_KEY = "runType";

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

    // 대기 건너뛰기 실행 허용 여부 (#389). 평소에는 꺼 두고 발표·연동 테스트 때만 .env로 켬
    // 켜지 않으면 15일 대기(구매확정 뒤 분쟁·금액 오류를 바로잡을 시간)를 건너뛴 실제 입금이 운영에서 나갈 수 있음
    // 설정이 없을 때는 꺼진 쪽(false)으로 둠. 서버가 뜰 때 한 번 읽으므로 값을 바꾸면 재시작해야 함
    private final boolean skipWaitRunEnabled;

    public PayoutJobRunner(JobOperator jobOperator, Job payoutJob, Clock clock,
                           @Value("${payout.skip-wait-run.enabled:false}") boolean skipWaitRunEnabled) {
        this.jobOperator = jobOperator;
        this.payoutJob = payoutJob;
        this.clock = clock;
        this.skipWaitRunEnabled = skipWaitRunEnabled;
    }

    // 정산 Job 1회 실행 공통 규칙
    // 요청 시각을 파라미터로 넣어 실행마다 새 JobInstance로 기록되게 함
    // 실패한 Job은 이어서 재시작(restart)하지 않고 새로 재실행함
    // 미정산 조건(정산 항목에 없는 후보)으로 이미 정산된 후보는 다시 고르지 않으므로 재실행해도 이중 정산되지 않음
    // (이 조건은 snapshotStep이 스냅샷 대상을 고를 때 적용됨. 재실행하면 새 스냅샷이 만들어지고, 이전 실행의 draft는 snapshot_id가 달라 섞이지 않음)

    /**
     * 매일 정기 정산을 실행한다 (스케줄러용).
     */
    public JobExecution runScheduled() throws Exception {
        return start(LocalDateTime.now(clock), PayoutRunType.SCHEDULED);
    }

    /**
     * 관리자가 요청한 정산을 실행한다. 대기 기간과 조회 범위는 정기 실행과 같다.
     */
    public JobExecution runManual() throws Exception {
        return start(LocalDateTime.now(clock), PayoutRunType.MANUAL);
    }

    /**
     * 대기 기간(15일)을 기다리지 않고 지금 있는 미정산 후보를 정산한다 (발표·연동 테스트용, #224).
     * 정산 Job은 requestedAt의 날짜를 "오늘"로 보고 상한을 계산하므로, 이 값만 미래로 넣어 대기를 건너뛴다.
     * 서버 시계, 정산 결과의 생성 시각, 다음 정산의 하한(마지막 정산 생성 시각 기준)은 실제 시각을 그대로 쓴다.
     * 스냅샷의 조회 상한(periodTo)도 미래 날짜로 기록되며, 실행 유형 {@code SKIP_WAIT}로 평소 실행과 구분한다.
     * 이미 정산된 후보는 미정산 조건으로 걸러지므로 여러 번 실행해도 이중 정산되지 않는다.
     *
     * @throws RestApiException 설정이 꺼져 있으면 {@code SKIP_WAIT_RUN_DISABLED} (Job을 만들지 않음)
     */
    public JobExecution runSkippingWait() throws Exception {
        // 컨트롤러가 아니라 여기서 막아, 어느 경로로 불러도 꺼져 있으면 Job이 시작되지 않게 함
        if (!skipWaitRunEnabled) {
            throw new RestApiException(PayoutErrorCode.SKIP_WAIT_RUN_DISABLED);
        }
        return start(LocalDateTime.now(clock).plusDays(SKIP_WAIT_SHIFT_DAYS), PayoutRunType.SKIP_WAIT);
    }

    // requestedAt: 식별용 파라미터 → 실행마다 새 JobInstance
    // runType: 기록용(non-identifying) 파라미터 → "같은 실행인가"를 가르는 값이 아니라 어느 경로였는지 남기는 값
    //   배치 실행 기록(BATCH_JOB_EXECUTION_PARAMS)에도 남고, 스냅샷 Tasklet이 읽어 payout_snapshot.run_type에 저장함
    private JobExecution start(LocalDateTime requestedAt, PayoutRunType runType) throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addLocalDateTime(REQUESTED_AT_KEY, requestedAt)
                .addString(RUN_TYPE_KEY, runType.name(), false)
                .toJobParameters();
        return jobOperator.start(payoutJob, params);
    }
}
