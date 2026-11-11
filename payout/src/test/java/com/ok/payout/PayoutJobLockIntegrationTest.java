package com.ok.payout;

import com.ok.common.exception.RestApiException;
import com.ok.payout.adapter.in.batch.PayoutJobRunner;
import com.ok.payout.adapter.in.batch.PayoutJobScheduler;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 통합 테스트: 정산 Job 동시 실행 잠금(ShedLock)이 실제 MySQL에서 세 실행 경로를 함께 막는지 확인 (#223)
//
// [확인하는 것]
//   - 잠금이 "실행 중"이면 관리자 실행 API·대기 건너뛰기 API는 409, 정기 실행은 PAYOUT_JOB_ALREADY_RUNNING이고 배치가 돌지 않음
//     (정기 스케줄러 메서드는 그 예외를 받아 오류 없이 건너뜀)
//   - 잠금 시간이 지났으면(서버가 죽어 남은 잠금) 막지 않고 정상 실행됨
//   - 실행이 동기라 runManual()이 돌아올 때 Job은 끝나 있고 잠금도 이미 풀려 있음 (비동기로 바뀌면 여기서 깨짐)
//   - 두 스레드가 동시에 시작하면 정확히 하나만 실행되고 다른 하나는 PAYOUT_JOB_ALREADY_RUNNING
//
// [잠금 행을 직접 다루는 이유]
// 잠금 표(shedlock)는 ShedLock이 쓰는 표라 엔티티·리포지토리가 없어 JdbcTemplate으로 넣고 고침
// 시각은 DB 시각과 비교하므로(PayoutJobLockConfig의 usingDbTime) JVM 시각이 아닌 UTC_TIMESTAMP(3)으로 만듦
//
// [배치가 돌았는지 보는 방법]
// PayoutRunIntegrationTest 위의 설명과 같음 (정산 Job 실행 횟수를 세고, 정산할 데이터는 넣지 않음)
// 대기 건너뛰기 스위치는 PayoutSkipWaitRunIntegrationTest와 같이 module-test.yml로 켜진 상태에 기댐
class PayoutJobLockIntegrationTest extends AbstractIntegrationTest {

    private static final String RUN_URL = "/api/v1/admin/payouts/run";
    private static final String SKIP_WAIT_RUN_URL = "/api/v1/admin/payouts/run/skip-wait";
    // PayoutJobConfig에서 만든 정산 Job 이름이자 PayoutJobRunner의 잠금 이름
    private static final String PAYOUT_JOB_NAME = "payoutJob";
    private static final String LOCK_TABLE = PayoutSchema.NAME + ".shedlock";
    // 공통 처리기가 PayoutErrorCode의 slug로 type을 만듦 (type 앞부분은 설정마다 달라 끝부분만 비교)
    private static final String ALREADY_RUNNING_TYPE = "/payout-job-already-running";
    // 동시 시작 테스트에서 기다리는 최대 시간. 정상이면 1초 안에 끝나고, 넘으면 멈춘 것이라 실패시킴
    private static final long WAIT_SECONDS = 30;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private TestAccessTokens tokens;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private PayoutJobRunner payoutJobRunner;

    @Autowired
    private PayoutJobScheduler payoutJobScheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    // 보안 필터를 적용해야 토큰 검증이 실제와 같게 동작한다 (DEVELOPMENT.md "테스트에서 토큰 보내기")
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        releaseLock();
    }

    // 다른 통합 테스트 클래스도 같은 컨텍스트에서 정산을 돌리므로, 클래스 순서와 상관없이 잠금을 풀어 둠
    // DELETE하지 않는 이유: 같은 컨텍스트의 LockProvider가 "행이 이미 있다"고 기억해 INSERT를 다시 하지 않으므로,
    // 행을 지우면 재시작 전까지 UPDATE만 시도하다 계속 잠금을 못 잡음
    @AfterEach
    void tearDown() {
        releaseLock();
    }

    // API: POST /api/v1/admin/payouts/run (정산 전체 수동 실행)
    // 준비: 잠금 행이 "실행 중"(lock_until = 지금 + 10분)
    // 확인: 409·에러 type이고 정산 Job이 실행되지 않았는지
    @Test
    void 정산이_실행_중이면_관리자_실행은_409이고_배치가_돌지_않는다() throws Exception {
        // given
        holdLock();
        int runCountBefore = payoutJobRunCount();

        // when & then
        mockMvc.perform(post(RUN_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(1L, "ADMIN")))
                .andExpect(status().isConflict())                          // 409
                .andExpect(jsonPath("$.type").value(endsWith(ALREADY_RUNNING_TYPE)));

        // then
        assertThat(payoutJobRunCount()).isEqualTo(runCountBefore);
    }

    // API: POST /api/v1/admin/payouts/run/skip-wait (정산 대기 건너뛰기 실행)
    // 준비: 잠금 행이 "실행 중"
    // 확인: 스위치가 켜져 있어도 409·에러 type이고 정산 Job이 실행되지 않았는지
    @Test
    void 정산이_실행_중이면_대기_건너뛰기_실행은_409이고_배치가_돌지_않는다() throws Exception {
        // given
        holdLock();
        int runCountBefore = payoutJobRunCount();

        // when & then
        mockMvc.perform(post(SKIP_WAIT_RUN_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(1L, "ADMIN")))
                .andExpect(status().isConflict())                          // 409
                .andExpect(jsonPath("$.type").value(endsWith(ALREADY_RUNNING_TYPE)));

        // then
        assertThat(payoutJobRunCount()).isEqualTo(runCountBefore);
    }

    @Test
    void 정산이_실행_중이면_정기_실행은_이미_실행_중_예외이고_배치가_돌지_않는다() {
        // given
        holdLock();
        int runCountBefore = payoutJobRunCount();

        // when & then
        assertThatThrownBy(() -> payoutJobRunner.runScheduled())
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_JOB_ALREADY_RUNNING));
        assertThat(payoutJobRunCount()).isEqualTo(runCountBefore);
    }

    // 새벽 정기 실행이 관리자 실행과 겹치는 것은 정상 상황이라, 스케줄러가 오류를 내지 않고 건너뛰어야 함
    @Test
    void 정산이_실행_중이면_정기_스케줄러는_오류_없이_건너뛴다() {
        // given
        holdLock();
        int runCountBefore = payoutJobRunCount();

        // when & then
        assertThatCode(() -> payoutJobScheduler.runDaily()).doesNotThrowAnyException();
        assertThat(payoutJobRunCount()).isEqualTo(runCountBefore);
    }

    // 서버가 실행 중에 죽어 풀지 못한 잠금은 lock_until이 지나면 다음 실행을 막지 않아야 함
    @Test
    void 잠금_시간이_지났으면_정상_실행된다() throws Exception {
        // given
        expiredLock();
        int runCountBefore = payoutJobRunCount();

        // when
        JobExecution sut = payoutJobRunner.runManual();

        // then
        assertThat(sut.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(payoutJobRunCount()).isEqualTo(runCountBefore + 1);
    }

    // 잠금은 Job이 동기로 끝날 때까지만 유지되는 것이 전제라, 돌아온 시점에 Job이 끝나 있어야 함
    @Test
    void 관리자_실행이_돌아오면_Job은_끝나_있다() throws Exception {
        // when
        JobExecution sut = payoutJobRunner.runManual();

        // then
        assertThat(sut.getStatus().isRunning()).isFalse();
        assertThat(sut.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    // lockAtLeastFor가 0이라 끝나면 바로 풀려야, 관리자가 정산 직후 다시 실행할 수 있음
    @Test
    void 관리자_실행이_돌아오면_잠금이_풀려_있다() throws Exception {
        // when
        payoutJobRunner.runManual();

        // then
        assertThat(isLockHeld()).isFalse();
    }

    // 두 스레드를 동시에 출발시키고, 이긴 쪽 Job을 payout_snapshot 표 잠금(LOCK TABLES)으로 붙잡아 끝나지 못하게 함
    // 그래서 진 쪽이 시도하는 순간 이긴 쪽은 반드시 잠금을 쥔 채 실행 중이라, 타이밍과 상관없이 결과가 정해짐
    // (표를 붙잡지 않으면 빈 데이터의 Job은 금방 끝나 진 쪽이 늦게 들어오면 둘 다 성공할 수 있음)
    @Test
    void 두_스레드가_동시에_시작하면_하나만_실행된다() throws Exception {
        // given
        int runCountBefore = payoutJobRunCount();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<JobExecution>> futures = new ArrayList<>();
        int executedCount = 0;
        int rejectedCount = 0;
        Future<JobExecution> rejected = null;

        try (Connection holder = dataSource.getConnection(); Statement statement = holder.createStatement()) {
            statement.execute("LOCK TABLES " + PayoutSchema.NAME + ".payout_snapshot WRITE");
            try {
                // when
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return payoutJobRunner.runManual();
                    }));
                }
                ready.await();
                go.countDown();

                // 진 쪽은 곧바로 거절되고, 이긴 쪽은 표 잠금에 막혀 잠금을 쥔 채 머묾
                rejected = waitUntilOneDone(futures);
                assertThat(isLockHeld()).isTrue();
                // 앱이 실제로 쓴 잠금 행의 유지 시간이 5분(lockAtMostFor)이어야 죽은 서버의 잠금이 5분 뒤 풀린다는 뜻이 됨
                Long heldMicros = jdbcTemplate.queryForObject(
                        "SELECT TIMESTAMPDIFF(MICROSECOND, locked_at, lock_until) FROM " + LOCK_TABLE + " WHERE name = ?",
                        Long.class, PAYOUT_JOB_NAME);
                assertThat(heldMicros).isEqualTo(300_000_000L);
                assertThatThrownBy(rejected::get)
                        .isInstanceOf(ExecutionException.class)
                        .cause()
                        .isInstanceOfSatisfying(RestApiException.class,
                                e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_JOB_ALREADY_RUNNING));
                rejectedCount++;
            } finally {
                statement.execute("UNLOCK TABLES");
            }

            for (Future<JobExecution> future : futures) {
                if (future == rejected) {
                    continue;
                }
                JobExecution execution = future.get(WAIT_SECONDS, TimeUnit.SECONDS);
                assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                executedCount++;
            }
        } finally {
            pool.shutdownNow();
        }

        // then
        assertThat(executedCount).isEqualTo(1);
        assertThat(rejectedCount).isEqualTo(1);
        assertThat(payoutJobRunCount()).isEqualTo(runCountBefore + 1);
    }

    // 둘 중 먼저 끝난 실행을 돌려줌. 둘 다 끝나거나 하나도 안 끝나면 잠금이 동시 실행을 막지 못한 것이라 실패시킴
    private Future<JobExecution> waitUntilOneDone(List<Future<JobExecution>> futures) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(WAIT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            List<Future<JobExecution>> done = new ArrayList<>();
            for (Future<JobExecution> future : futures) {
                if (future.isDone()) {
                    done.add(future);
                }
            }
            if (done.size() == 1) {
                return done.get(0);
            }
            if (done.size() > 1) {
                fail("표가 잠긴 동안 두 실행이 모두 끝남: 잠금이 동시 실행을 막지 못함");
            }
            Thread.sleep(50);
        }
        return fail(WAIT_SECONDS + "초 안에 어느 실행도 끝나지 않음");
    }

    // 정산 Job 실행 횟수 (PayoutRunIntegrationTest.payoutJobRunCount와 같은 이유로 findJobInstances 사용)
    private int payoutJobRunCount() {
        return jobRepository.findJobInstances(PAYOUT_JOB_NAME).size();
    }

    // 다른 서버가 정산을 실행 중인 상태
    // 이 컨텍스트에서 행이 아직 없을 수도 있어 INSERT하되, 있으면 시각만 바꿈 (행이 있어도 ShedLock은 INSERT 실패 뒤 UPDATE로 잡음)
    private void holdLock() {
        upsertLock("UTC_TIMESTAMP(3) + INTERVAL 10 MINUTE");
    }

    // 실행하던 서버가 죽어 풀지 못하고 시간이 지난 잠금
    private void expiredLock() {
        upsertLock("UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE");
    }

    private void upsertLock(String lockUntilExpression) {
        jdbcTemplate.update(
                "INSERT INTO " + LOCK_TABLE + " (name, lock_until, locked_at, locked_by)"
                        + " VALUES (?, " + lockUntilExpression + ", UTC_TIMESTAMP(3), 'test')"
                        + " ON DUPLICATE KEY UPDATE lock_until = " + lockUntilExpression
                        + ", locked_at = UTC_TIMESTAMP(3), locked_by = 'test'",
                PAYOUT_JOB_NAME);
    }

    private void releaseLock() {
        jdbcTemplate.update(
                "UPDATE " + LOCK_TABLE + " SET lock_until = UTC_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE name = ?",
                PAYOUT_JOB_NAME);
    }

    private boolean isLockHeld() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + LOCK_TABLE + " WHERE name = ? AND lock_until > UTC_TIMESTAMP(3)",
                Integer.class, PAYOUT_JOB_NAME);
        return count != null && count > 0;
    }
}
