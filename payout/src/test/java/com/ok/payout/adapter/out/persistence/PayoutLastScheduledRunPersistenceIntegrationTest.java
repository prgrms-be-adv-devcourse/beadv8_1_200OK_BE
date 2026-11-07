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
// 배치 메타 행은 이 클래스 전용 번호대(9_390_000~)로 넣어 그 범위만 지운다.
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

    // 배치 실행 1건(인스턴스·실행·requestedAt 파라미터)과 그 스냅샷, 필요하면 정산 1건을 넣는다
    // requestedAt은 Spring Batch가 LocalDateTime을 저장하는 ISO_LOCAL_DATE_TIME 글자 그대로 넣음
    private void insertRun(long executionId, String status, String runType, String requestedAt, boolean withPayout) {
        jdbcTemplate.update("INSERT INTO " + PayoutSchema.NAME + ".BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) "
                + "VALUES (?, 0, 'payoutJobTest390', ?)", executionId, "key-" + executionId);
        jdbcTemplate.update("INSERT INTO " + PayoutSchema.NAME + ".BATCH_JOB_EXECUTION "
                + "(JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, STATUS) "
                + "VALUES (?, 0, ?, NOW(6), ?)", executionId, executionId, status);
        jdbcTemplate.update("INSERT INTO " + PayoutSchema.NAME + ".BATCH_JOB_EXECUTION_PARAMS "
                + "(JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE, IDENTIFYING) "
                + "VALUES (?, 'requestedAt', 'java.time.LocalDateTime', ?, 'Y')", executionId, requestedAt);
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

    // 외래키 순서대로 지운다 (정산 → 스냅샷 → 배치 메타). 배치 메타는 이 테스트 번호대만 지움
    private void cleanUp() {
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
                ID_START, ID_END);
    }
}
