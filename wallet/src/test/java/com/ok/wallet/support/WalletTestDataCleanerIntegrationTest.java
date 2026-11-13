package com.ok.wallet.support;

import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

// 검사 SQL이 일부러 만든 어긋남을 잡고 정상 지갑은 통과시키는지 확인한다. 이 검사를 믿어야 다른 지갑 시험의 정리 단계 검사도 믿을 수 있다.
class WalletTestDataCleanerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    private WalletTestDataCleaner sut;

    @BeforeEach
    void setUp() {
        sut = new WalletTestDataCleaner(jdbcTemplate);
        sut.deleteAll();
    }

    // 이 시험은 일부러 어긋난 데이터를 만들므로 검사 없이 지운다
    @AfterEach
    void tearDown() {
        sut.deleteAll();
    }

    @Test
    void 잔액과_원장이_맞는_지갑은_어긋남이_없다() {
        // given
        long buyerWalletId = insertWallet(WalletKind.BUYER, 1L, 8_000);
        insertLedger(WalletKind.BUYER, buyerWalletId, "CHARGE", 10_000, 10_000);
        insertLedger(WalletKind.BUYER, buyerWalletId, "PAYMENT", 3_000, 7_000);
        insertLedger(WalletKind.BUYER, buyerWalletId, "REFUND", 1_000, 8_000);
        long sellerWalletId = insertWallet(WalletKind.SELLER, 2L, 7_000);
        insertLedger(WalletKind.SELLER, sellerWalletId, "PAYOUT", 5_000, 5_000);
        insertLedger(WalletKind.SELLER, sellerWalletId, "PAYOUT", 2_000, 7_000);
        insertWallet(WalletKind.BUYER, 3L, 0);

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("지갑_종류")
    void 잔액만_틀린_지갑을_잡는다(WalletKind kind) {
        // given
        long walletId = insertWallet(kind, 1L, 6_000);
        long ledgerId = insertLedger(kind, walletId, kind.increaseType(), 5_000, 5_000);

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("wallet_type"), row -> row.get("check_item"), row -> row.get("ledger_id"),
                        row -> row.get("expected"), row -> row.get("actual"))
                .containsExactly(tuple(kind.name(), "LAST_LEDGER_BALANCE", ledgerId, "6000", "5000"));
    }

    static Stream<WalletKind> 지갑_종류() {
        return Stream.of(WalletKind.BUYER, WalletKind.SELLER);
    }

    // 잠금이 빠져 두 요청이 같은 잔액을 읽고 각자 더하면, 마지막 원장은 잔액과 맞아도 앞뒤 줄이 이어지지 않는다
    @ParameterizedTest
    @MethodSource("지갑_종류")
    void 원장_앞뒤_줄이_이어지지_않으면_잡는다(WalletKind kind) {
        // given
        long walletId = insertWallet(kind, 1L, 5_000);
        insertLedger(kind, walletId, kind.increaseType(), 5_000, 5_000);
        long brokenLedgerId = insertLedger(kind, walletId, kind.increaseType(), 1_000, 5_000);

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("wallet_type"), row -> row.get("check_item"), row -> row.get("ledger_id"),
                        row -> row.get("expected"), row -> row.get("actual"))
                .containsExactly(tuple(kind.name(), "LEDGER_CHAIN", brokenLedgerId, "6000", "5000"));
    }

    @ParameterizedTest
    @MethodSource("지갑_종류")
    void 원장_없이_잔액만_있는_지갑을_잡는다(WalletKind kind) {
        // given
        insertWallet(kind, 1L, 3_000);

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("wallet_type"), row -> row.get("check_item"),
                        row -> row.get("expected"), row -> row.get("actual"))
                .containsExactly(tuple(kind.name(), "LAST_LEDGER_BALANCE", "3000", "0"));
    }

    // 출금처럼 새 유형이 생겼는데 검사 SQL에 증감 방향을 넣지 않으면 조용히 넘어가지 않게 한다
    @ParameterizedTest
    @MethodSource("지갑_종류")
    void 증감_방향을_정하지_않은_원장_유형을_잡는다(WalletKind kind) {
        // given
        long walletId = insertWallet(kind, 1L, 1_000);
        long ledgerId = insertLedger(kind, walletId, "WITHDRAW", 1_000, 1_000);

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("wallet_type"), row -> row.get("check_item"), row -> row.get("ledger_id"),
                        row -> row.get("actual"))
                .containsExactly(tuple(kind.name(), "UNKNOWN_LEDGER_TYPE", ledgerId, "WITHDRAW"));
    }

    @Test
    void 어긋남이_있으면_데이터를_모두_지운_뒤_실패한다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 6_000);
        insertLedger(WalletKind.BUYER, walletId, "CHARGE", 5_000, 5_000);

        // when & then
        assertThatThrownBy(() -> sut.verifyAndDeleteAll())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("LAST_LEDGER_BALANCE");
        assertThat(countWalletRows()).isZero();
    }

    @Test
    void 어긋남이_없으면_데이터를_모두_지우고_통과한다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 5_000);
        insertLedger(WalletKind.BUYER, walletId, "CHARGE", 5_000, 5_000);

        // when
        sut.verifyAndDeleteAll();

        // then
        assertThat(countWalletRows()).isZero();
    }

    private long insertWallet(WalletKind kind, long memberId, long balance) {
        jdbcTemplate.update("INSERT INTO " + kind.walletTable()
                + " (member_id, balance, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))", memberId, balance);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM " + kind.walletTable() + " WHERE member_id = ?", Long.class, memberId);
    }

    private long insertLedger(WalletKind kind, long walletId, String type, long amount, long balanceAfter) {
        String referenceKey = type + ":" + walletId + "-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO " + kind.ledgerTable() + " (" + kind.walletIdColumn()
                        + ", type, amount, balance_after, reference_key, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                walletId, type, amount, balanceAfter, referenceKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM " + kind.ledgerTable() + " WHERE reference_key = ?", Long.class, referenceKey);
    }

    private long countWalletRows() {
        return Stream.of("wallet_order_payment", "wallet_charge", "buyer_wallet_ledger", "seller_wallet_ledger",
                        "buyer_wallet", "seller_wallet")
                .mapToLong(table -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class))
                .sum();
    }

    enum WalletKind {
        BUYER("buyer_wallet", "CHARGE"),
        SELLER("seller_wallet", "PAYOUT");

        private final String walletTable;
        private final String increaseType;

        WalletKind(String walletTable, String increaseType) {
            this.walletTable = walletTable;
            this.increaseType = increaseType;
        }

        String walletTable() {
            return walletTable;
        }

        String ledgerTable() {
            return walletTable + "_ledger";
        }

        String walletIdColumn() {
            return walletTable + "_id";
        }

        String increaseType() {
            return increaseType;
        }
    }
}
