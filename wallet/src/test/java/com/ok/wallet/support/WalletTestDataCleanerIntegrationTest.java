package com.ok.wallet.support;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.domain.BuyerWalletLedgerType;
import com.ok.wallet.domain.SellerWalletLedgerType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
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
        insertCharge(1L, 10_000, "COMPLETED", "pay-1");
        insertLedger(WalletKind.BUYER, buyerWalletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1");
        insertOrderPayment(100L, 1L, 3_000, "PAID");
        insertLedger(WalletKind.BUYER, buyerWalletId, "PAYMENT", 3_000, 7_000, "PAYMENT:100");
        insertOrderPayment(101L, 1L, 50_000, "FAILED");
        insertCharge(1L, 5_000, "FAILED", "pay-2");
        insertCharge(1L, 5_000, "IN_PROGRESS", null);
        insertOrderRefund(1L, 100L, 1L, 1_000, "COMPLETED");
        insertLedger(WalletKind.BUYER, buyerWalletId, "REFUND", 1_000, 8_000, "REFUND:1");
        insertOrderRefund(2L, 100L, 1L, 5_000, "FAILED");
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

    // 위 시험은 빠뜨린 유형이 원장에 실제로 들어가야 잡는다. 코드의 유형 목록을 직접 돌면 유형을 더한 순간 잡는다
    @ParameterizedTest
    @MethodSource("코드에_있는_원장_유형")
    void 코드에_있는_원장_유형은_모두_검사_SQL에_증감_방향이_있다(WalletKind kind, String type) {
        // given
        long walletId = insertWallet(kind, 1L, 1_000);
        insertLedger(kind, walletId, type, 1_000, 1_000, type + "-1");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then: 짝이 없어 다른 어긋남은 나오므로 유형 검사 결과만 본다
        assertThat(mismatches)
                .extracting(row -> row.get("check_item"))
                .doesNotContain("UNKNOWN_LEDGER_TYPE");
    }

    static Stream<Arguments> 코드에_있는_원장_유형() {
        return Stream.concat(
                Arrays.stream(BuyerWalletLedgerType.values()).map(type -> Arguments.of(WalletKind.BUYER, type.name())),
                Arrays.stream(SellerWalletLedgerType.values()).map(type -> Arguments.of(WalletKind.SELLER, type.name())));
    }

    @Test
    void 원장이_없는_완료_충전_건을_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 0);
        insertCharge(1L, 5_000, "COMPLETED", "pay-1");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("wallet_id"), row -> row.get("check_item"), row -> row.get("expected"))
                .containsExactly(tuple(walletId, "CHARGE_WITHOUT_LEDGER", "CHARGE:pay-1"));
    }

    // 토스 승인 뒤 실패로 처리된 충전 건은 잔액에 반영되면 안 된다
    @Test
    void 원장이_있는_실패_충전_건을_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 5_000);
        insertCharge(1L, 5_000, "FAILED", "pay-1");
        long ledgerId = insertLedger(WalletKind.BUYER, walletId, "CHARGE", 5_000, 5_000, "CHARGE:pay-1");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("check_item"), row -> row.get("ledger_id"))
                .containsExactly(tuple("FAILED_CHARGE_WITH_LEDGER", ledgerId));
    }

    // 토스 승인 없이 잔액이 오른 경우다
    @Test
    void 충전_건이_없는_충전_원장을_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 5_000);
        long ledgerId = insertLedger(WalletKind.BUYER, walletId, "CHARGE", 5_000, 5_000, "CHARGE:pay-1");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("wallet_id"), row -> row.get("check_item"), row -> row.get("ledger_id"))
                .containsExactly(tuple(walletId, "CHARGE_LEDGER_WITHOUT_RECORD", ledgerId));
    }

    // 처리 중 충전을 복구하는 작업이 잔액만 올리고 충전 건을 완료로 바꾸지 않은 경우다
    @Test
    void 처리_중인_충전_건에_붙은_충전_원장을_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 5_000);
        insertCharge(1L, 5_000, "IN_PROGRESS", "pay-1");
        long ledgerId = insertLedger(WalletKind.BUYER, walletId, "CHARGE", 5_000, 5_000, "CHARGE:pay-1");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("check_item"), row -> row.get("ledger_id"))
                .containsExactly(tuple("CHARGE_LEDGER_WITHOUT_RECORD", ledgerId));
    }

    @Test
    void 원장이_없는_결제_성공_기록을_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 0);
        insertOrderPayment(100L, 1L, 3_000, "PAID");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("wallet_id"), row -> row.get("check_item"), row -> row.get("expected"))
                .containsExactly(tuple(walletId, "PAID_WITHOUT_LEDGER", "PAYMENT:100"));
    }

    @ParameterizedTest
    @MethodSource("차감하지_않기로_한_결제_상태")
    void 원장이_있는_결제_실패_또는_만료_기록을_잡는다(String status) {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 7_000);
        insertLedger(WalletKind.BUYER, walletId, "CHARGE", 10_000, 10_000);
        insertOrderPayment(100L, 1L, 3_000, status);
        long ledgerId = insertLedger(WalletKind.BUYER, walletId, "PAYMENT", 3_000, 7_000, "PAYMENT:100");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("check_item"), row -> row.get("ledger_id"))
                .containsExactly(tuple("FAILED_PAYMENT_WITH_LEDGER", ledgerId));
    }

    // 잔액 부족과 결제 만료는 둘 다 차감하지 않기로 한 기록이다
    static Stream<String> 차감하지_않기로_한_결제_상태() {
        return Stream.of("FAILED", "EXPIRED");
    }

    @Test
    void 결제_처리_기록이_없는_결제_원장을_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 7_000);
        insertLedger(WalletKind.BUYER, walletId, "CHARGE", 10_000, 10_000);
        long ledgerId = insertLedger(WalletKind.BUYER, walletId, "PAYMENT", 3_000, 7_000, "PAYMENT:100");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("check_item"), row -> row.get("ledger_id"))
                .containsExactly(tuple("PAYMENT_LEDGER_WITHOUT_RECORD", ledgerId));
    }

    @Test
    void 충전_건과_원장의_금액이_다르면_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 5_000);
        insertCharge(1L, 3_000, "COMPLETED", "pay-1");
        long ledgerId = insertLedger(WalletKind.BUYER, walletId, "CHARGE", 5_000, 5_000, "CHARGE:pay-1");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("check_item"), row -> row.get("ledger_id"),
                        row -> row.get("expected"), row -> row.get("actual"))
                .containsExactly(tuple("CHARGE_LEDGER_MISMATCH", ledgerId,
                        "member=1, amount=3000", "member=1, amount=5000"));
    }

    // 충전 건은 회원 1의 것인데 원장은 회원 2의 지갑에 들어간 경우다
    @Test
    void 충전_건과_원장의_주인이_다르면_잡는다() {
        // given
        insertWallet(WalletKind.BUYER, 1L, 0);
        long otherWalletId = insertWallet(WalletKind.BUYER, 2L, 5_000);
        insertCharge(1L, 5_000, "COMPLETED", "pay-1");
        long ledgerId = insertLedger(WalletKind.BUYER, otherWalletId, "CHARGE", 5_000, 5_000, "CHARGE:pay-1");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("check_item"), row -> row.get("ledger_id"),
                        row -> row.get("expected"), row -> row.get("actual"))
                .containsExactly(tuple("CHARGE_LEDGER_MISMATCH", ledgerId,
                        "member=1, amount=5000", "member=2, amount=5000"));
    }

    @Test
    void 결제_기록과_원장의_금액이_다르면_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 5_000);
        insertLedger(WalletKind.BUYER, walletId, "CHARGE", 10_000, 10_000);
        insertOrderPayment(100L, 1L, 3_000, "PAID");
        long ledgerId = insertLedger(WalletKind.BUYER, walletId, "PAYMENT", 5_000, 5_000, "PAYMENT:100");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("check_item"), row -> row.get("ledger_id"),
                        row -> row.get("expected"), row -> row.get("actual"))
                .containsExactly(tuple("PAYMENT_LEDGER_MISMATCH", ledgerId,
                        "member=1, amount=3000", "member=1, amount=5000"));
    }

    // 결제 기록은 회원 1의 것인데 회원 2의 지갑에서 빠져나간 경우다
    @Test
    void 결제_기록과_원장의_주인이_다르면_잡는다() {
        // given
        insertWallet(WalletKind.BUYER, 1L, 0);
        long otherWalletId = insertWallet(WalletKind.BUYER, 2L, 7_000);
        insertLedger(WalletKind.BUYER, otherWalletId, "CHARGE", 10_000, 10_000);
        insertOrderPayment(100L, 1L, 3_000, "PAID");
        long ledgerId = insertLedger(WalletKind.BUYER, otherWalletId, "PAYMENT", 3_000, 7_000, "PAYMENT:100");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("check_item"), row -> row.get("ledger_id"),
                        row -> row.get("expected"), row -> row.get("actual"))
                .containsExactly(tuple("PAYMENT_LEDGER_MISMATCH", ledgerId,
                        "member=1, amount=3000", "member=2, amount=3000"));
    }

    @Test
    void 원장이_없는_반환_완료_기록을_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 0);
        insertOrderRefund(7L, 100L, 1L, 3_000, "COMPLETED");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("wallet_id"), row -> row.get("check_item"), row -> row.get("expected"))
                .containsExactly(tuple(walletId, "REFUNDED_WITHOUT_LEDGER", "REFUND:7"));
    }

    @Test
    void 반환_기록과_원장의_금액이_다르면_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 5_000);
        insertOrderRefund(7L, 100L, 1L, 3_000, "COMPLETED");
        long ledgerId = insertLedger(WalletKind.BUYER, walletId, "REFUND", 5_000, 5_000, "REFUND:7");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("check_item"), row -> row.get("ledger_id"),
                        row -> row.get("expected"), row -> row.get("actual"))
                .containsExactly(tuple("REFUND_LEDGER_MISMATCH", ledgerId,
                        "member=1, amount=3000", "member=1, amount=5000"));
    }

    @Test
    void 원장이_있는_반환_거절_기록을_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 3_000);
        insertOrderRefund(7L, 100L, 1L, 3_000, "FAILED");
        long ledgerId = insertLedger(WalletKind.BUYER, walletId, "REFUND", 3_000, 3_000, "REFUND:7");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("check_item"), row -> row.get("ledger_id"))
                .containsExactly(tuple("FAILED_REFUND_WITH_LEDGER", ledgerId));
    }

    @Test
    void 반환_처리_기록이_없는_반환_원장을_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 3_000);
        long ledgerId = insertLedger(WalletKind.BUYER, walletId, "REFUND", 3_000, 3_000, "REFUND:7");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("check_item"), row -> row.get("ledger_id"))
                .containsExactly(tuple("REFUND_LEDGER_WITHOUT_RECORD", ledgerId));
    }

    @Test
    void 돌려준_환불을_다시_가져온_되돌리기_기록에_원장이_없으면_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 0);
        insertLedger(WalletKind.BUYER, walletId, "REFUND", 3_000, 3_000, "REFUND:7");
        insertOrderRefund(7L, 100L, 1L, 3_000, "COMPLETED");
        insertOrderRefundRevert(7L, 100L, 1L, 3_000, "REVERTED");
        jdbcTemplate.update("UPDATE buyer_wallet SET balance = 3000 WHERE id = ?", walletId);

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("wallet_id"), row -> row.get("check_item"), row -> row.get("expected"))
                .containsExactly(tuple(walletId, "REVERTED_WITHOUT_LEDGER", "REFUND_REVERT:7"));
    }

    @Test
    void 돌려준_적_없는_환불의_되돌리기_기록은_원장이_없어도_맞다() {
        // given: 반환이 거절된 환불을 되돌림. 가져올 돈이 없어 원장을 남기지 않는다
        insertWallet(WalletKind.BUYER, 1L, 0);
        insertOrderRefund(7L, 100L, 1L, 3_000, "FAILED");
        insertOrderRefundRevert(7L, 100L, 1L, 3_000, "REVERTED");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches).isEmpty();
    }

    @Test
    void 되돌리기_기록과_원장의_금액이_다르면_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 0);
        insertLedger(WalletKind.BUYER, walletId, "REFUND", 3_000, 3_000, "REFUND:7");
        long ledgerId = insertLedger(WalletKind.BUYER, walletId, "REFUND_REVERT", 3_000, 0, "REFUND_REVERT:7");
        insertOrderRefund(7L, 100L, 1L, 3_000, "COMPLETED");
        insertOrderRefundRevert(7L, 100L, 1L, 2_000, "REVERTED");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("check_item"), row -> row.get("ledger_id"),
                        row -> row.get("expected"), row -> row.get("actual"))
                .containsExactlyInAnyOrder(
                        tuple("REVERT_LEDGER_MISMATCH", ledgerId, "member=1, amount=2000", "member=1, amount=3000"),
                        tuple("REVERT_RECORD_MISMATCH", null, "member=1, amount=3000", "member=1, amount=2000"));
    }

    @Test
    void 되돌리기_기록의_구매자가_반환_기록과_다르면_잡는다() {
        // given: 돈을 받은 구매자 1번이 아니라 2번 지갑에서 가져감
        long walletId = insertWallet(WalletKind.BUYER, 1L, 3_000);
        insertLedger(WalletKind.BUYER, walletId, "REFUND", 3_000, 3_000, "REFUND:7");
        long otherWalletId = insertWallet(WalletKind.BUYER, 2L, 0);
        insertLedger(WalletKind.BUYER, otherWalletId, "REFUND", 3_000, 3_000, "REFUND:8");
        insertLedger(WalletKind.BUYER, otherWalletId, "REFUND_REVERT", 3_000, 0, "REFUND_REVERT:7");
        insertOrderRefund(7L, 100L, 1L, 3_000, "COMPLETED");
        insertOrderRefund(8L, 200L, 2L, 3_000, "COMPLETED");
        insertOrderRefundRevert(7L, 100L, 2L, 3_000, "REVERTED");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("wallet_id"), row -> row.get("check_item"),
                        row -> row.get("expected"), row -> row.get("actual"))
                .containsExactly(tuple(otherWalletId, "REVERT_RECORD_MISMATCH",
                        "member=1, amount=3000", "member=2, amount=3000"));
    }

    @ParameterizedTest
    @MethodSource("원장이_붙으면_안_되는_되돌리기")
    void 돈을_가져오지_않은_되돌리기에_붙은_원장을_잡는다(String refundStatus, String revertStatus) {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 0);
        insertLedger(WalletKind.BUYER, walletId, "REFUND", 3_000, 3_000, "REFUND:7");
        long ledgerId = insertLedger(WalletKind.BUYER, walletId, "REFUND_REVERT", 3_000, 0, "REFUND_REVERT:7");
        insertOrderRefund(7L, 100L, 1L, 3_000, refundStatus);
        insertOrderRefundRevert(7L, 100L, 1L, 3_000, revertStatus);

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then: 반환 거절 기록에 붙은 반환 원장은 따로 잡히므로 되돌리기 원장 줄만 본다
        assertThat(mismatches)
                .filteredOn(row -> "REVERT_LEDGER_WITHOUT_RECORD".equals(row.get("check_item")))
                .extracting(row -> row.get("ledger_id"))
                .containsExactly(ledgerId);
    }

    static Stream<Arguments> 원장이_붙으면_안_되는_되돌리기() {
        return Stream.of(
                Arguments.of("COMPLETED", "FAILED"),
                Arguments.of("FAILED", "REVERTED"));
    }

    @Test
    void 되돌리기_기록이_없는_되돌리기_원장을_잡는다() {
        // given
        long walletId = insertWallet(WalletKind.BUYER, 1L, 0);
        insertLedger(WalletKind.BUYER, walletId, "REFUND", 3_000, 3_000, "REFUND:7");
        long ledgerId = insertLedger(WalletKind.BUYER, walletId, "REFUND_REVERT", 3_000, 0, "REFUND_REVERT:7");
        insertOrderRefund(7L, 100L, 1L, 3_000, "COMPLETED");

        // when
        List<Map<String, Object>> mismatches = sut.findMismatches();

        // then
        assertThat(mismatches)
                .extracting(row -> row.get("check_item"), row -> row.get("ledger_id"))
                .containsExactly(tuple("REVERT_LEDGER_WITHOUT_RECORD", ledgerId));
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

    // 짝 맞추기와 상관없는 원장은 참조 키가 겹치지 않게 아무 값이나 붙인다.
    // 다만 충전 원장은 완료 충전 건이 없으면 어긋남으로 잡히므로 짝이 되는 충전 건도 넣는다
    private long insertLedger(WalletKind kind, long walletId, String type, long amount, long balanceAfter) {
        if (kind == WalletKind.BUYER && type.equals("CHARGE")) {
            String paymentKey = "pay-" + walletId + "-" + System.nanoTime();
            Long memberId = jdbcTemplate.queryForObject(
                    "SELECT member_id FROM buyer_wallet WHERE id = ?", Long.class, walletId);
            insertCharge(memberId, amount, "COMPLETED", paymentKey);
            return insertLedger(kind, walletId, type, amount, balanceAfter, "CHARGE:" + paymentKey);
        }
        return insertLedger(kind, walletId, type, amount, balanceAfter, type + "-" + walletId + "-" + System.nanoTime());
    }

    private long insertLedger(WalletKind kind, long walletId, String type, long amount, long balanceAfter,
                              String referenceKey) {
        jdbcTemplate.update("INSERT INTO " + kind.ledgerTable() + " (" + kind.walletIdColumn()
                        + ", type, amount, balance_after, reference_key, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
                walletId, type, amount, balanceAfter, referenceKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM " + kind.ledgerTable() + " WHERE reference_key = ?", Long.class, referenceKey);
    }

    private void insertCharge(long memberId, long amount, String status, String paymentKey) {
        jdbcTemplate.update("""
                INSERT INTO wallet_charge (charge_no, member_id, amount, status, payment_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))
                """, "CHG_" + System.nanoTime(), memberId, amount, status, paymentKey);
    }

    private void insertOrderPayment(long orderId, long buyerId, long amount, String status) {
        jdbcTemplate.update("""
                INSERT INTO wallet_order_payment (order_id, buyer_id, amount, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, NOW(6), NOW(6))
                """, orderId, buyerId, amount, status);
    }

    private void insertOrderRefund(long refundId, long orderId, long buyerId, long amount, String status) {
        jdbcTemplate.update("""
                INSERT INTO wallet_order_refund (refund_id, order_id, buyer_id, amount, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))
                """, refundId, orderId, buyerId, amount, status);
    }

    private void insertOrderRefundRevert(long refundId, long orderId, long buyerId, long amount, String status) {
        jdbcTemplate.update("""
                INSERT INTO wallet_order_refund_revert (refund_id, order_id, buyer_id, amount, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))
                """, refundId, orderId, buyerId, amount, status);
    }

    private long countWalletRows() {
        return Stream.of("wallet_order_refund_revert", "wallet_order_refund", "wallet_order_payment", "wallet_charge", "buyer_wallet_ledger",
                        "seller_wallet_ledger", "buyer_wallet", "seller_wallet")
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
