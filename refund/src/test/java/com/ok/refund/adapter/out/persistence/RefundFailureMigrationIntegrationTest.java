package com.ok.refund.adapter.out.persistence;

import com.ok.common.config.ModuleFlyway;
import com.ok.testsupport.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 마이그레이션 V202611141632(옛 실패 칸 → 단계 진행 표, #574 결정 3)가 옛 데이터를 맞게 옮기는지 실제 MySQL로 확인한다.
// 테스트 DB는 빈 상태에서 Flyway가 돌아 옮길 행이 없으므로, 앱이 쓰는 스키마와 별개인 임시 스키마에서
// 단계 표를 만든 버전(V202611141555)까지만 올리고 옛 칸에 행을 넣은 뒤 나머지를 적용한다.
// 앱 계정은 모듈 스키마에만 권한이 있어 임시 스키마는 root로 만든다. Testcontainers MySQL은 root 비밀번호를 앱 계정 비밀번호와 같게 둔다.
// Flyway는 RefundFlywayConfig와 같은 ModuleFlyway.create로 만들어, 테스트 설정(위치·out-of-order 등)이 모듈 테스트와 같게 한다
class RefundFailureMigrationIntegrationTest extends AbstractIntegrationTest {

    private static final String SCRATCH_SCHEMA = "refund_failure_migration_test";
    private static final String BEFORE_MOVE_VERSION = "202611141555";

    @Autowired
    JdbcConnectionDetails connectionDetails;
    @Autowired
    Environment environment;

    private DriverManagerDataSource rootDataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        rootDataSource = new DriverManagerDataSource(connectionDetails.getJdbcUrl(), "root", connectionDetails.getPassword());
        jdbc = new JdbcTemplate(rootDataSource);
        jdbc.execute("DROP DATABASE IF EXISTS " + SCRATCH_SCHEMA);
        jdbc.execute("CREATE DATABASE " + SCRATCH_SCHEMA + " DEFAULT CHARACTER SET utf8mb4");
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP DATABASE IF EXISTS " + SCRATCH_SCHEMA);
    }

    @Test
    void 옛_실패_칸을_단계_진행_표의_멈춤_줄로_옮기고_칸을_지운다() {
        // given: 단계 표는 있지만 옛 실패 칸도 아직 있는 상태
        migrate(BEFORE_MOVE_VERSION);
        long processingProductFailure = insertLegacyRefund(1, "PROCESSING", "PRODUCT", "PRODUCT_API_FAILED");
        long completedWalletFailure = insertLegacyRefund(2, "COMPLETED", "WALLET", "WALLET_REFUND_FAILED");
        long processingWithoutFailure = insertLegacyRefund(3, "PROCESSING", null, null);
        // #574 뒤에 같은 단계 줄이 먼저 만들어진 환불. 그 줄이 더 최신이라 옮기면 안 되고, 옮기면 UNIQUE 위반으로 마이그레이션이 멈춘다
        long alreadyHasStep = insertLegacyRefund(4, "PROCESSING", "ORDER", "ORDER_API_FAILED");
        jdbc.update("INSERT INTO " + SCRATCH_SCHEMA + ".refund_step (refund_id, step, status, attempt_count, next_check_at, last_reason,"
                        + " created_at, updated_at) VALUES (?, 'ORDER', 'READY', 2, '2026-11-14 11:00:00', 'NEWER_REASON',"
                        + " '2026-11-14 10:00:00', '2026-11-14 10:30:00')",
                alreadyHasStep);

        // when
        migrate(null);

        // then
        List<Map<String, Object>> sut = jdbc.queryForList(
                "SELECT refund_id, step, status, attempt_count, next_check_at, last_reason FROM " + SCRATCH_SCHEMA
                        + ".refund_step ORDER BY refund_id, step");
        assertThat(sut).hasSize(3);
        assertSoftly(softly -> {
            softly.assertThat(sut.get(0)).containsEntry("refund_id", processingProductFailure)
                    .containsEntry("step", "PRODUCT").containsEntry("status", "STOPPED")
                    .containsEntry("attempt_count", 1).containsEntry("next_check_at", null)
                    .containsEntry("last_reason", "PRODUCT_API_FAILED");
            // 끝난 환불의 실패도 이력으로 옮긴다
            softly.assertThat(sut.get(1)).containsEntry("refund_id", completedWalletFailure)
                    .containsEntry("step", "WALLET").containsEntry("status", "STOPPED")
                    .containsEntry("attempt_count", 1).containsEntry("next_check_at", null)
                    .containsEntry("last_reason", "WALLET_REFUND_FAILED");
            // 먼저 있던 줄은 그대로다
            softly.assertThat(sut.get(2)).containsEntry("refund_id", alreadyHasStep)
                    .containsEntry("step", "ORDER").containsEntry("status", "READY")
                    .containsEntry("attempt_count", 2).containsEntry("last_reason", "NEWER_REASON");
        });
        assertThat(sut).extracting(row -> row.get("refund_id")).doesNotContain(processingWithoutFailure);
        Integer oldColumns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'refund'"
                        + " AND column_name IN ('failed_step', 'failure_code')",
                Integer.class, SCRATCH_SCHEMA);
        assertThat(oldColumns).isZero();
    }

    // target이 null이면 끝까지 올린다
    private void migrate(String target) {
        Flyway base = ModuleFlyway.create(rootDataSource, environment, SCRATCH_SCHEMA, "refund");
        var configuration = Flyway.configure().configuration(base.getConfiguration());
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    // 옛 칸이 있던 시절의 환불 한 건. active_order_item_id는 처리 중일 때만 채우던 칸이라 유일 제약을 피하려고 행마다 다른 값을 쓴다
    private long insertLegacyRefund(int seq, String status, String failedStep, String failureCode) {
        jdbc.update("INSERT INTO " + SCRATCH_SCHEMA + ".refund (order_id, order_item_id, quantity, buyer_id, seller_id, product_option_id,"
                        + " product_name, option_name, unit_price, item_amount, status, active_order_item_id, failed_step, failure_code,"
                        + " created_at, updated_at) VALUES (70, ?, 1, 1, 7, 77, '상품', '옵션', 10000, 10000, ?, ?, ?, ?,"
                        + " '2026-11-13 10:00:00', '2026-11-13 10:00:00')",
                700 + seq, status, "PROCESSING".equals(status) ? 700L + seq : null, failedStep, failureCode);
        return jdbc.queryForObject("SELECT id FROM " + SCRATCH_SCHEMA + ".refund WHERE order_item_id = ?", Long.class, 700 + seq);
    }
}
