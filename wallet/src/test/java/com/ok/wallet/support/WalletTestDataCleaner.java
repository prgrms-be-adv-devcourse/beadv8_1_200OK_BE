package com.ok.wallet.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지갑 시험이 끝날 때 잔액과 원장이 맞는지 검사하고 지갑 데이터를 모두 지운다.
 * 시험 작성자가 무엇을 확인했든 같은 기준으로 검사하려고, 잔액을 바꾸는 시험은 정리 단계에서 이 클래스만 쓴다.
 * 통합 시험과 모듈 시험({@code @ApplicationModuleTest})이 함께 쓰도록 스프링 빈이 아니라 {@link JdbcTemplate}을 받는 보통 클래스로 둔다.
 */
public class WalletTestDataCleaner {

    private static final String CHECK_SQL = loadCheckSql();

    // 원장이 지갑을 외래 키로 참조하므로 원장을 먼저 지운다
    private static final List<String> DELETE_SQLS = List.of(
            "DELETE FROM wallet_order_payment",
            "DELETE FROM wallet_charge",
            "DELETE FROM buyer_wallet_ledger",
            "DELETE FROM seller_wallet_ledger",
            "DELETE FROM buyer_wallet",
            "DELETE FROM seller_wallet");

    private final JdbcTemplate jdbcTemplate;

    public WalletTestDataCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 잔액과 원장이 맞는지 검사한 뒤 지갑 데이터를 모두 지운다.
     * 검사 결과와 관계없이 먼저 지우고 나서 실패시킨다. 어긋난 데이터가 남으면 다음 시험이 상관없는 이유로 줄줄이 실패하기 때문이다.
     *
     * @throws AssertionError 어긋난 줄이 있으면 그 줄을 모두 담아 던진다
     */
    public void verifyAndDeleteAll() {
        List<Map<String, Object>> mismatches;
        try {
            mismatches = findMismatches();
        } finally {
            deleteAll();
        }
        assertThat(mismatches)
                .as("지갑 잔액과 원장이 어긋났습니다 (sql/wallet-balance-check.sql)")
                .isEmpty();
    }

    /**
     * 잔액과 원장이 어긋난 줄을 돌려준다. 데이터는 지우지 않는다.
     *
     * @return 줄마다 {@code wallet_type}, {@code wallet_id}, {@code check_item}, {@code ledger_id}, {@code expected}, {@code actual}. 모두 맞으면 빈 목록
     */
    public List<Map<String, Object>> findMismatches() {
        return jdbcTemplate.queryForList(CHECK_SQL);
    }

    /**
     * 검사 없이 지갑 데이터를 모두 지운다.
     * 시험 시작 전처럼 앞선 시험이 남긴 데이터를 치울 때 쓴다. 남은 데이터는 앞선 시험의 정리 단계에서 이미 검사했다.
     */
    public void deleteAll() {
        DELETE_SQLS.forEach(jdbcTemplate::update);
    }

    // JDBC는 한 번에 한 문장만 실행하므로, DB 도구에서 실행하려고 붙인 끝의 세미콜론을 뗀다
    private static String loadCheckSql() {
        try {
            String sql = new ClassPathResource("sql/wallet-balance-check.sql")
                    .getContentAsString(StandardCharsets.UTF_8)
                    .strip();
            return sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
