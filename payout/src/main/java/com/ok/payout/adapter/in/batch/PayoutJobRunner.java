package com.ok.payout.adapter.in.batch;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.domain.type.PayoutRunType;
import com.ok.payout.exception.PayoutErrorCode;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class PayoutJobRunner {

    // 대기 건너뛰기 실행 때 실행 기준 시각을 미래로 미는 일수 (#224)
    // 상한 = 실행일 − 대기 일수 00:00 이므로, 대기 일수 + 1이면 상한이 내일 00:00이 되어 오늘 생긴 후보까지 들어옴
    // 자정 직전에 실행해도 그 사이 생긴 후보가 빠지지 않도록 하루 더 여유를 둠 (대기 일수 + 2 = 17일)
    private static final int SKIP_WAIT_SHIFT_DAYS = PayoutPolicy.SETTLEMENT_WAITING_DAYS + 2;

    private static final String LOCK_NAME = "payoutJob";

    // 연장(KeepAlive)이 실행 중 계속 늘려 주므로, 이 값은 서버가 죽어 연장이 멈췄을 때 잠금이 풀리는 시간임
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(5);

    // 끝나면 바로 풂: 관리자가 정산 직후 다시 실행할 수 있어야 함
    private static final Duration LOCK_AT_LEAST_FOR = Duration.ZERO;

    private final JobOperator jobOperator;
    private final Job payoutJob;

    // 정산 Job 동시 실행 방지용 잠금 실행기 (#223)
    private final LockingTaskExecutor lockingTaskExecutor;

    // 현재 시각 기준 (전역 ClockConfig, JVM 시간대 = Asia/Seoul)
    // requestedAt의 날짜가 곧 정산 실행일(runDate)이 되어 정산 범위를 정하므로, 실행 환경과 상관없이 같은 시간대여야 함
    // 예) 스케줄러는 02:00 KST에 실행되는데 now()가 UTC(전날 17:00)를 주면 실행일이 하루 앞당겨져 다른 후보가 정산됨 (#93)
    private final Clock clock;

    // 대기 건너뛰기 실행 허용 여부 (payout.skip-wait-run.enabled, 기본 꺼짐, #485)
    // monolith는 정산 설정 파일을 읽지 않아 이 값이 없으므로 기본값 false가 적용되어 기본은 꺼짐
    // (단, 같은 이름의 환경 변수 PAYOUT_SKIP_WAIT_RUN_ENABLED가 넘어오면 monolith에서도 켜짐 — 로컬 compose는 .env를 통째로 넘김)
    private final boolean skipWaitRunEnabled;

    // @RequiredArgsConstructor 대신 직접 만든 이유: 생성자 파라미터에 @Value를 붙여야 하고,
    // 단위 테스트에서 스프링 없이 켜짐·꺼짐을 인자로 바로 넘길 수 있음
    public PayoutJobRunner(
            JobOperator jobOperator,
            Job payoutJob,
            LockingTaskExecutor lockingTaskExecutor,
            Clock clock,
            @Value("${payout.skip-wait-run.enabled:false}") boolean skipWaitRunEnabled
    ) {
        this.jobOperator = jobOperator;
        this.payoutJob = payoutJob;
        this.lockingTaskExecutor = lockingTaskExecutor;
        this.clock = clock;
        this.skipWaitRunEnabled = skipWaitRunEnabled;
    }

    // 새벽 정기 스케줄러가 부르는 정산 Job 1회 실행 (실행 유형 SCHEDULED)
    // 실행 유형은 호출자가 enum을 넘기지 않고 메서드가 정함: 잘못된 유형을 넣을 여지를 없애고, 웹 입구가 domain 타입을 몰라도 됨
    // 요청 시각을 파라미터로 넣어 실행마다 새 JobInstance로 기록되게 함
    // 실패한 Job은 이어서 재시작(restart)하지 않고 새로 재실행함
    // 미정산 조건(정산 항목에 없는 후보)으로 이미 정산된 후보는 다시 고르지 않으므로 재실행해도 이중 정산되지 않음
    // (이 조건은 snapshotStep이 스냅샷 대상을 고를 때 적용됨. 재실행하면 새 스냅샷이 만들어지고, 이전 실행의 draft는 snapshot_id가 달라 섞이지 않음)
    public JobExecution runScheduled() throws Exception {
        return start(LocalDateTime.now(clock), PayoutRunType.SCHEDULED);
    }

    // 관리자 실행 API(POST /api/v1/admin/payouts/run)가 부르는 정산 Job 1회 실행 (실행 유형 MANUAL)
    // 시각 기준과 재실행 규칙은 runScheduled()와 같고, 정기 실행과 구분해 기록하려고 메서드를 나눔
    public JobExecution runManual() throws Exception {
        return start(LocalDateTime.now(clock), PayoutRunType.MANUAL);
    }

    // 대기 기간(15일)을 기다리지 않고 지금 있는 미정산 후보를 정산함 (발표·연동 테스트용, #224)
    // 정산 Job은 requestedAt의 날짜를 "오늘"로 보고 상한을 계산하므로, 이 값만 미래로 넣어 대기를 건너뜀
    // 서버 시계와 정산 결과의 생성 시각은 실제 시각을 그대로 씀
    // 다음 정산의 조회 하한은 정기 실행의 요청일 기준이라 이 실행(SKIP_WAIT)은 하한 기준에서 빠짐 (#390, 미래 요청일이 하한을 앞으로 튀게 하지 않도록)
    //   정산 생성 시각은 정기 실행 기록이 없을 때의 대체 기준으로만 쓰임
    // 스냅샷의 조회 상한(periodTo)도 미래 날짜로 기록되므로, 평소 실행과 구분하려고 실행 유형 SKIP_WAIT를 Job 파라미터로 남김
    // 이미 정산된 후보는 미정산 조건으로 걸러지므로 여러 번 실행해도 이중 정산되지 않음
    // 운영에서 실수로 호출되어도 정산 대기 규칙이 깨지지 않도록, 스위치가 꺼져 있으면 Job을 시작하기 전에 409로 거절함 (#485)
    // 컨트롤러가 아닌 여기서 막는 이유: 이 메서드를 부르는 다른 경로가 생겨도 같은 스위치가 적용됨
    public JobExecution runSkippingWait() throws Exception {
        if (!skipWaitRunEnabled) {
            throw new RestApiException(PayoutErrorCode.SKIP_WAIT_RUN_DISABLED);
        }
        return start(LocalDateTime.now(clock).plusDays(SKIP_WAIT_SHIFT_DAYS), PayoutRunType.SKIP_WAIT);
    }

    // runType은 JobInstance를 가르는 키(identifying)가 아닌 기록용(false)으로 넣음: 인스턴스는 requestedAt만으로 충분히 달라짐
    // 두 파라미터가 빠지거나 잘못된 값이면 PayoutJobParametersValidator가 Job 시작 전에 막음
    // 정기·관리자·대기 건너뛰기 세 경로가 모두 지나는 곳에서 잠가, 어느 경로끼리든 동시에 두 번 돌지 않게 함 (#223)
    //   정산 1건 실행 API(지금 501)를 열 때도 이 메서드를 지나야 같은 잠금이 적용됨
    // Job이 동기로 실행되므로 잠금이 Job 끝까지 유지됨. Job을 비동기로 바꾸면 잠금이 바로 풀리니 함께 바꿔야 함
    private JobExecution start(LocalDateTime requestedAt, PayoutRunType runType) throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addLocalDateTime("requestedAt", requestedAt)
                .addString("runType", runType.name(), false)
                .toJobParameters();
        // KeepAliveLockProvider가 만료를 ShedLock 자체 시계로 판단하므로, 주입 Clock이 달라도 연장이 끊기지 않게 같은 시계를 씀
        LockConfiguration lockConfig = new LockConfiguration(
                ClockProvider.now(), LOCK_NAME, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR);

        LockingTaskExecutor.TaskResult<JobExecution> result;
        try {
            result = lockingTaskExecutor.executeWithLock(
                    () -> jobOperator.start(payoutJob, params), lockConfig);
        } catch (Exception | Error e) {
            // executeWithLock이 Throwable을 던지므로, Job이 던진 예외는 바꾸지 않고 그대로 보냄
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }

        // 잠금을 못 잡아도 ShedLock은 예외를 던지지 않고 결과만 비워 돌려주므로 여기서 직접 거절함
        if (!result.wasExecuted()) {
            throw new RestApiException(PayoutErrorCode.PAYOUT_JOB_ALREADY_RUNNING);
        }
        return result.getResult();
    }
}
