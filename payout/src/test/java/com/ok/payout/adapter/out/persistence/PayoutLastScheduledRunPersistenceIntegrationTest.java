package com.ok.payout.adapter.out.persistence;

import com.ok.payout.PayoutSchema;
import com.ok.payout.application.port.out.PayoutQueryRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// "마지막 정기 실행의 requestedAt" 네이티브 쿼리를 실제 MySQL + Flyway 마이그레이션으로 확인 (#390)
// 배치 메타 테이블에는 리포지토리가 없어 JdbcTemplate으로 직접 넣는다.
// 5번(조건에 맞는 실행 없음)이 정산 테이블 전체 상태에 기대므로 테스트 전후로 정산 테이블을 비우고,
// 배치 메타 행은 이 클래스 전용 번호대(실행 9_390_000~, 인스턴스 8_390_000~)로 넣어 그 범위만 지운다.
class PayoutLastScheduledRunPersistenceIntegrationTest extends AbstractIntegrationTest {

    private static final long ID_START = 9_390_000L;
    private static final long ID_END = 9_399_999L;

    @Autowired
    private PayoutQueryRepository sut;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 정기_완료_정산있음_실행이_둘이면_실행ID가_큰_쪽의_requestedAt을_준다() {
        // given
        insertRun(9_390_001L, "COMPLETED", "SCHEDULED", "2026-11-03T02:00:00", true);
        insertRun(9_390_002L, "COMPLETED", "SCHEDULED", "2026-11-10T02:00:00", true);

        // when
        Optional<LocalDateTime> result = sut.findLastCompletedScheduledRequestedAt();

        // then
        assertThat(result).contains(LocalDateTime.of(2026, 11, 10, 2, 0));
    }

    @Test
    void 가장_최근_실행이_FAILED면_건너뛰고_그_전_성공_실행을_준다() {
        // given
        insertRun(9_390_001L, "COMPLETED", "SCHEDULED", "2026-11-03T02:00:00", true);
        insertRun(9_390_002L, "FAILED", "SCHEDULED", "2026-11-10T02:00:00", true);

        // when
        Optional<LocalDateTime> result = sut.findLastCompletedScheduledRequestedAt();

        // then
        assertThat(result).contains(LocalDateTime.of(2026, 11, 3, 2, 0));
    }

    @Test
    void MANUAL과_SKIP_WAIT_실행은_완료되고_정산이_있어도_건너뛴다() {
        // given
        insertRun(9_390_001L, "COMPLETED", "SCHEDULED", "2026-11-03T02:00:00", true);
        insertRun(9_390_002L, "COMPLETED", "MANUAL", "2026-11-08T10:00:00", true);
        insertRun(9_390_003L, "COMPLETED", "SKIP_WAIT", "2026-11-25T10:00:00", true); // +17일 미래 값

        // when
        Optional<LocalDateTime> result = sut.findLastCompletedScheduledRequestedAt();

        // then
        assertThat(result).contains(LocalDateTime.of(2026, 11, 3, 2, 0));
    }

    @Test
    void 정기_완료여도_정산이_0건인_실행은_건너뛴다() {
        // given
        insertRun(9_390_001L, "COMPLETED", "SCHEDULED", "2026-11-03T02:00:00", true);
        insertRun(9_390_002L, "COMPLETED", "SCHEDULED", "2026-11-10T02:00:00", false);

        // when
        Optional<LocalDateTime> result = sut.findLastCompletedScheduledRequestedAt();

        // then
        assertThat(result).contains(LocalDateTime.of(2026, 11, 3, 2, 0));
    }

    @Test
    void 조건에_맞는_실행이_없으면_빈_값을_준다() {
        // given: 실패, 수동, 정산 0건만 있음
        insertRun(9_390_001L, "FAILED", "SCHEDULED", "2026-11-03T02:00:00", true);
        insertRun(9_390_002L, "COMPLETED", "MANUAL", "2026-11-08T10:00:00", true);
        insertRun(9_390_003L, "COMPLETED", "SCHEDULED", "2026-11-10T02:00:00", false);

        // when
        Optional<LocalDateTime> result = sut.findLastCompletedScheduledRequestedAt();

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void 실행ID가_큰_쪽의_requestedAt이_더_이르면_그_이른_값을_준다() {
        // given: 최신 판단은 requestedAt 값이 아니라 실행 ID(큰 쪽)라는 결정을 고정한다. 큰 ID 쪽이 일부러 더 이른 시각
        insertRun(9_390_001L, "COMPLETED", "SCHEDULED", "2026-11-10T02:00:00", true);
        insertRun(9_390_002L, "COMPLETED", "SCHEDULED", "2026-11-03T02:00:00", true);

        // when
        Optional<LocalDateTime> result = sut.findLastCompletedScheduledRequestedAt();

        // then
        assertThat(result).contains(LocalDateTime.of(2026, 11, 3, 2, 0));
    }

    @Test
    void runType_파라미터가_없는_옛_실행은_스냅샷이_SCHEDULED여도_건너뛴다() {
        // given: #485 이전 실행은 runType 잡 파라미터가 없고, 마이그레이션이 스냅샷 run_type만 SCHEDULED로 채웠다.
        //        옛 대기 건너뛰기 실행의 requestedAt(+17일 미래)이 하한이 되면 안 됨
        insertRun(9_390_001L, "COMPLETED", "SCHEDULED", "2026-11-03T02:00:00", true);
        insertRunWithoutRunTypeParam(9_390_002L, "COMPLETED", "SCHEDULED", "2026-11-25T10:00:00", true);

        // when
        Optional<LocalDateTime> result = sut.findLastCompletedScheduledRequestedAt();

        // then
        assertThat(result).contains(LocalDateTime.of(2026, 11, 3, 2, 0));
    }

    @Test
    void runType_파라미터가_없는_옛_실행만_있으면_빈_값을_준다() {
        // given
        insertRunWithoutRunTypeParam(9_390_001L, "COMPLETED", "SCHEDULED", "2026-11-25T10:00:00", true);

        // when
        Optional<LocalDateTime> result = sut.findLastCompletedScheduledRequestedAt();

        // then
        assertThat(result).isEmpty();
    }

    // 실제 Job처럼 requestedAt과 runType 파라미터를 모두 가진 실행 1건을 넣는다
    private void insertRun(long executionId, String status, String runType, String requestedAt, boolean withPayout) {
        insertRun(executionId, status, runType, requestedAt, withPayout, true);
    }

    // #485 이전 실행처럼 runType 파라미터가 없는 실행 (스냅샷 run_type만 마이그레이션이 채움)
    private void insertRunWithoutRunTypeParam(long executionId, String status, String runType, String requestedAt,
                                              boolean withPayout) {
        insertRun(executionId, status, runType, requestedAt, withPayout, false);
    }

    // 배치 실행 1건(인스턴스·실행·파라미터)과 그 스냅샷, 필요하면 정산 1건을 넣는다
    // 인스턴스 ID는 실행 ID에서 1_000_000을 뺀 다른 번호대(8_390_000~): 같은 숫자면 JOB_INSTANCE_ID로 잘못 이어도 테스트가 통과함
    // 파라미터는 실제 Job처럼 requestedAt(LocalDateTime, Spring Batch가 저장하는 ISO 글자)과 runType(String)을 넣고,
    //   requestedAt보다 먼저 날짜처럼 생긴 decoyAt을 넣어 PARAMETER_NAME 조건이 빠지면 엉뚱한 값이 나오거나 파싱이 깨지게 함
    private void insertRun(long executionId, String status, String runType, String requestedAt, boolean withPayout,
                           boolean withRunTypeParam) {
        long instanceId = executionId - 1_000_000L;
        jdbcTemplate.update("INSERT INTO " + PayoutSchema.NAME + ".BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) "
                + "VALUES (?, 0, 'payoutJobTest390', ?)", instanceId, "key-" + executionId);
        jdbcTemplate.update("INSERT INTO " + PayoutSchema.NAME + ".BATCH_JOB_EXECUTION "
                + "(JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, STATUS) "
                + "VALUES (?, 0, ?, NOW(6), ?)", executionId, instanceId, status);
        insertParam(executionId, "decoyAt", "java.time.LocalDateTime", "2001-01-01T00:00:00", "N");
        insertParam(executionId, "requestedAt", "java.time.LocalDateTime", requestedAt, "Y");
        if (withRunTypeParam) {
            insertParam(executionId, "runType", "java.lang.String", runType, "N");
        }
        // 스냅샷·정산은 SQL로 직접 넣음: job_execution_id를 위에서 넣은 배치 실행 ID(이 클래스 번호대)와 맞춰야 쿼리가 둘을 이어 주기 때문
        jdbcTemplate.update("INSERT INTO payout_snapshot "
                + "(job_execution_id, period_to, created_at, updated_at, run_type) "
                + "VALUES (?, NOW(6), NOW(6), NOW(6), ?)", executionId, runType);
        if (withPayout) {
            Long snapshotId = jdbcTemplate.queryForObject(
                    "SELECT id FROM payout_snapshot WHERE job_execution_id = ?", Long.class, executionId);
            jdbcTemplate.update("INSERT INTO payout (snapshot_id, payee_id, is_system, amount, created_at, updated_at) "
                    + "VALUES (?, 1, FALSE, 1000, NOW(6), NOW(6))", snapshotId);
        }
    }

    private void insertParam(long executionId, String name, String type, String value, String identifying) {
        jdbcTemplate.update("INSERT INTO " + PayoutSchema.NAME + ".BATCH_JOB_EXECUTION_PARAMS "
                + "(JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE, IDENTIFYING) "
                + "VALUES (?, ?, ?, ?, ?)", executionId, name, type, value, identifying);
    }

    // 외래키 순서대로 지운다 (정산 → 스냅샷 → 배치 메타). 배치 메타는 이 테스트 번호대만 지움
    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM payout_outbox");
        jdbcTemplate.update("DELETE FROM payout_item");
        jdbcTemplate.update("DELETE FROM payout");
        jdbcTemplate.update("DELETE FROM payout_seller_draft_item");
        jdbcTemplate.update("DELETE FROM payout_seller_draft");
        jdbcTemplate.update("DELETE FROM payout_system_draft_item");
        jdbcTemplate.update("DELETE FROM payout_failure");
        jdbcTemplate.update("DELETE FROM payout_snapshot_item");
        jdbcTemplate.update("DELETE FROM payout_snapshot");
        jdbcTemplate.update("DELETE FROM " + PayoutSchema.NAME + ".BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID BETWEEN ? AND ?",
                ID_START, ID_END);
        jdbcTemplate.update("DELETE FROM " + PayoutSchema.NAME + ".BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID BETWEEN ? AND ?",
                ID_START, ID_END);
        jdbcTemplate.update("DELETE FROM " + PayoutSchema.NAME + ".BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID BETWEEN ? AND ?",
                ID_START - 1_000_000L, ID_END - 1_000_000L);
    }
}
