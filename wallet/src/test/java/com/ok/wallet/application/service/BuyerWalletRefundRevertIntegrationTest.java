package com.ok.wallet.application.service;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.application.dto.OrderRefundResult;
import com.ok.wallet.application.dto.OrderRefundRevertResult;
import com.ok.wallet.domain.WalletOrderRefundFailureReason;
import com.ok.wallet.domain.WalletOrderRefundRevertFailureReason;
import com.ok.wallet.domain.WalletOrderRefundRevertStatus;
import com.ok.wallet.domain.WalletOrderRefundStatus;
import com.ok.wallet.support.WalletTestCharger;
import com.ok.wallet.support.WalletTestDataCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 환불 되돌리기 처리(#611). 동시 요청에서 각 트랜잭션이 실제로 커밋되어야 하므로 테스트에 @Transactional을 붙이지 않는다
class BuyerWalletRefundRevertIntegrationTest extends AbstractIntegrationTest {
    private static final Long BUYER_ID = 8_001L;
    private static final Long ORDER_ID = 80_001L;

    @Autowired
    BuyerWalletService buyerWalletService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private WalletTestCharger charger;

    @BeforeEach
    void setUp() {
        charger = new WalletTestCharger(jdbcTemplate, buyerWalletService);
        new WalletTestDataCleaner(jdbcTemplate).deleteAll();
    }

    @AfterEach
    void tearDown() {
        new WalletTestDataCleaner(jdbcTemplate).verifyAndDeleteAll();
    }

    @Test
    void 돌려준_환불을_되돌리면_잔액이_줄고_원장에_남는다() {
        // given: 50,000원 충전, 35,000원 결제, 7,000원 반환 → 잔액 22,000원
        refundedOrder(1L, 7_000);

        // when
        OrderRefundRevertResult sut = buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(WalletOrderRefundRevertStatus.REVERTED);
            softly.assertThat(sut.failureReason()).isNull();
            softly.assertThat(sut.buyerId()).isEqualTo(BUYER_ID);
            softly.assertThat(sut.amount()).isEqualTo(7_000);
            softly.assertThat(sut.alreadyProcessed()).isFalse();
            softly.assertThat(balanceOf(BUYER_ID)).isEqualTo(15_000);
            softly.assertThat(latestLedger(BUYER_ID)).isEqualTo("REFUND_REVERT|REFUND_REVERT:1|7000|15000");
        });
    }

    @Test
    void 잔액이_모자라면_가져오지_않고_잔액_부족으로_실패한다() {
        // given: 7,000원을 돌려받은 뒤 다른 주문에 20,000원을 써서 잔액 2,000원
        refundedOrder(1L, 7_000);
        buyerWalletService.payForOrder(BUYER_ID, ORDER_ID + 1, 20_000);

        // when
        OrderRefundRevertResult sut = buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // then: 잔액을 마이너스로 두지 않고 남은 2,000원도 가져오지 않는다
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(WalletOrderRefundRevertStatus.FAILED);
            softly.assertThat(sut.failureReason()).isEqualTo(WalletOrderRefundRevertFailureReason.INSUFFICIENT_BALANCE);
            softly.assertThat(sut.alreadyProcessed()).isFalse();
            softly.assertThat(balanceOf(BUYER_ID)).isEqualTo(2_000);
            softly.assertThat(countLedgersByReferenceKey("REFUND_REVERT:1")).isZero();
        });
    }

    @Test
    void 잔액과_되돌릴_금액이_같으면_모두_가져온다() {
        // given: 잔액 22,000원 중 15,000원을 써서 반환 금액 7,000원만 남음
        refundedOrder(1L, 7_000);
        buyerWalletService.payForOrder(BUYER_ID, ORDER_ID + 1, 15_000);

        // when
        OrderRefundRevertResult sut = buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // then
        assertThat(sut.status()).isEqualTo(WalletOrderRefundRevertStatus.REVERTED);
        assertThat(balanceOf(BUYER_ID)).isZero();
    }

    @Test
    void 반환_요청보다_먼저_온_되돌리기는_원장_변화_없이_다시_가져옴으로_답한다() {
        // given: 결제만 하고 반환 요청은 아직 오지 않음
        paidOrder();

        // when
        OrderRefundRevertResult sut = buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // then: 반환 기록에 거절을 먼저 넣어 뒤에 올 반환 요청을 막는다
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(WalletOrderRefundRevertStatus.REVERTED);
            softly.assertThat(sut.buyerId()).isEqualTo(BUYER_ID);
            softly.assertThat(sut.amount()).isEqualTo(7_000);
            softly.assertThat(balanceOf(BUYER_ID)).isEqualTo(15_000);
            softly.assertThat(countLedgersByReferenceKey("REFUND_REVERT:1")).isZero();
            softly.assertThat(refundRecord(1L)).isEqualTo("FAILED|REFUND_REVERTED");
        });
    }

    @Test
    void 되돌린_뒤_늦게_온_반환_요청은_돈을_주지_않고_되돌린_환불로_거절한다() {
        // given
        paidOrder();
        buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // when
        OrderRefundResult sut = buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(WalletOrderRefundStatus.FAILED);
            softly.assertThat(sut.failureReason()).isEqualTo(WalletOrderRefundFailureReason.REFUND_REVERTED);
            softly.assertThat(balanceOf(BUYER_ID)).isEqualTo(15_000);
            softly.assertThat(countLedgersByReferenceKey("REFUND:1")).isZero();
        });
    }

    @Test
    void 거절된_반환을_되돌리면_원장_변화_없이_다시_가져옴으로_답한다() {
        // given: 결제 금액보다 많아 반환이 거절됨
        paidOrder();
        buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 40_000);

        // when
        OrderRefundRevertResult sut = buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 40_000);

        // then: 처음 반환 거절은 그대로 둔다
        assertThat(sut.status()).isEqualTo(WalletOrderRefundRevertStatus.REVERTED);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(15_000);
        assertThat(refundRecord(1L)).isEqualTo("FAILED|REFUND_TOTAL_EXCEEDED");
    }

    @Test
    void 요청의_구매자와_금액이_달라도_반환_기록의_구매자_지갑에서_반환_금액만큼_가져온다() {
        // given: 구매자 8001번에게 7,000원을 돌려줌. 다른 구매자 8002번도 잔액이 있음
        Long otherBuyerId = 8_002L;
        refundedOrder(1L, 7_000);
        charger.charge(otherBuyerId, "pay-other", 30_000);

        // when: 환불이 다른 구매자와 금액으로 되돌리기를 요청함
        OrderRefundRevertResult sut = buyerWalletService.revertRefundForOrder(1L, ORDER_ID, otherBuyerId, 9_000);

        // then: 결과에도 실제로 돌려준 값이 나가 환불이 어긋남을 바로잡는다
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(WalletOrderRefundRevertStatus.REVERTED);
            softly.assertThat(sut.buyerId()).isEqualTo(BUYER_ID);
            softly.assertThat(sut.amount()).isEqualTo(7_000);
            softly.assertThat(balanceOf(BUYER_ID)).isEqualTo(15_000);
            softly.assertThat(balanceOf(otherBuyerId)).isEqualTo(30_000);
        });
    }

    @Test
    void 같은_되돌리기가_다시_오면_가져오지_않고_처음_결과를_돌려준다() {
        // given
        refundedOrder(1L, 7_000);
        buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // when
        OrderRefundRevertResult sut = buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(WalletOrderRefundRevertStatus.REVERTED);
            softly.assertThat(sut.alreadyProcessed()).isTrue();
            softly.assertThat(balanceOf(BUYER_ID)).isEqualTo(15_000);
            softly.assertThat(countLedgersByReferenceKey("REFUND_REVERT:1")).isEqualTo(1);
        });
    }

    @Test
    void 잔액_부족으로_실패한_되돌리기는_잔액이_채워진_뒤_다시_와도_처음_실패를_돌려준다() {
        // given: 잔액 부족으로 실패한 뒤 충전해 잔액이 충분해짐
        refundedOrder(1L, 7_000);
        buyerWalletService.payForOrder(BUYER_ID, ORDER_ID + 1, 20_000);
        buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);
        charger.charge(BUYER_ID, "pay-after", 10_000);

        // when
        OrderRefundRevertResult sut = buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // then: 환불은 처음 실패로 이미 다음 단계를 정했으므로 결과를 바꾸지 않는다
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(WalletOrderRefundRevertStatus.FAILED);
            softly.assertThat(sut.alreadyProcessed()).isTrue();
            softly.assertThat(balanceOf(BUYER_ID)).isEqualTo(12_000);
        });
    }

    @Test
    void 돌려준_뒤_되돌린_환불에_같은_반환_요청이_다시_오면_돈을_주지_않고_되돌린_환불로_거절한다() {
        // given
        refundedOrder(1L, 7_000);
        buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // when
        OrderRefundResult sut = buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // then: 처음 결과(돌려줌)를 다시 보내면 환불은 돈이 구매자에게 있다고 본다
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(WalletOrderRefundStatus.FAILED);
            softly.assertThat(sut.failureReason()).isEqualTo(WalletOrderRefundFailureReason.REFUND_REVERTED);
            softly.assertThat(sut.amount()).isEqualTo(7_000);
            softly.assertThat(sut.alreadyProcessed()).isTrue();
            softly.assertThat(balanceOf(BUYER_ID)).isEqualTo(15_000);
            softly.assertThat(refundRecord(1L)).isEqualTo("COMPLETED|");
        });
    }

    @Test
    void 되돌리기에_실패한_환불에_같은_반환_요청이_다시_오면_처음_결과를_돌려준다() {
        // given: 잔액 부족으로 되돌리지 못해 돈은 아직 구매자에게 있음
        refundedOrder(1L, 7_000);
        buyerWalletService.payForOrder(BUYER_ID, ORDER_ID + 1, 20_000);
        buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // when
        OrderRefundResult sut = buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // then
        assertThat(sut.status()).isEqualTo(WalletOrderRefundStatus.COMPLETED);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(2_000);
    }

    @Test
    void 다시_가져온_뒤_같은_주문의_새_환불은_합계_초과_없이_돌려준다() {
        // given: 결제 금액 35,000원을 모두 돌려준 뒤 되돌림
        refundedOrder(1L, 35_000);
        buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 35_000);

        // when: 같은 상품을 새 환불로 다시 돌려줌
        OrderRefundResult sut = buyerWalletService.refundForOrder(2L, ORDER_ID, BUYER_ID, 35_000);

        // then
        assertThat(sut.status()).isEqualTo(WalletOrderRefundStatus.COMPLETED);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(50_000);
    }

    @Test
    void 되돌리지_못한_환불은_반환_합계에_그대로_센다() {
        // given: 35,000원을 모두 돌려줬는데 구매자가 써서 되돌리지 못함
        refundedOrder(1L, 35_000);
        buyerWalletService.payForOrder(BUYER_ID, ORDER_ID + 1, 40_000);
        buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 35_000);

        // when
        OrderRefundResult sut = buyerWalletService.refundForOrder(2L, ORDER_ID, BUYER_ID, 1_000);

        // then: 돈이 구매자에게 남아 있으므로 더 돌려주면 결제 금액을 넘는다
        assertThat(sut.status()).isEqualTo(WalletOrderRefundStatus.FAILED);
        assertThat(sut.failureReason()).isEqualTo(WalletOrderRefundFailureReason.REFUND_TOTAL_EXCEEDED);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(10_000);
    }

    @ParameterizedTest
    @MethodSource("잘못된_되돌리기_요청")
    void 값이_잘못된_요청은_기록하지_않고_예외가_난다(Long refundId, Long orderId, Long memberId, long amount,
                                                String message) {
        // when & then
        assertThatThrownBy(() -> buyerWalletService.revertRefundForOrder(refundId, orderId, memberId, amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        assertThat(countRows("wallet_order_refund")).isZero();
        assertThat(countRows("wallet_order_refund_revert")).isZero();
    }

    static Stream<Arguments> 잘못된_되돌리기_요청() {
        return Stream.of(
                Arguments.of(null, 1L, 1L, 1_000L, "환불 ID, 주문 ID, 회원 ID는 필수입니다. refundId=null, orderId=1, memberId=1"),
                Arguments.of(1L, null, 1L, 1_000L, "환불 ID, 주문 ID, 회원 ID는 필수입니다. refundId=1, orderId=null, memberId=1"),
                Arguments.of(1L, 1L, null, 1_000L, "환불 ID, 주문 ID, 회원 ID는 필수입니다. refundId=1, orderId=1, memberId=null"),
                Arguments.of(1L, 1L, 1L, 0L, "되돌릴 금액은 0보다 커야 합니다. amount=0"),
                Arguments.of(1L, 1L, 1L, -1L, "되돌릴 금액은 0보다 커야 합니다. amount=-1"));
    }

    @Test
    void 같은_되돌리기가_동시에_와도_한_번만_가져온다() throws Exception {
        // given
        refundedOrder(1L, 7_000);
        List<Callable<OrderRefundRevertResult>> reverts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            reverts.add(() -> buyerWalletService.revertRefundForOrder(1L, ORDER_ID, BUYER_ID, 7_000));
        }

        // when
        List<OrderRefundRevertResult> sut = runConcurrently(reverts);

        // then
        assertThat(sut).filteredOn(result -> !result.alreadyProcessed()).hasSize(1);
        assertThat(sut).extracting(OrderRefundRevertResult::status)
                .containsOnly(WalletOrderRefundRevertStatus.REVERTED);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(15_000);
    }

    @Test
    void 반환_요청과_되돌리기가_동시에_와도_돈은_돌려주지_않은_것과_같아진다() throws Exception {
        // given: 다섯 환불마다 반환 요청과 되돌리기가 함께 옴
        paidOrder();
        List<Callable<Object>> requests = new ArrayList<>();
        for (long refundId = 1; refundId <= 5; refundId++) {
            long id = refundId;
            requests.add(() -> buyerWalletService.refundForOrder(id, ORDER_ID, BUYER_ID, 1_000));
            requests.add(() -> buyerWalletService.revertRefundForOrder(id, ORDER_ID, BUYER_ID, 1_000));
        }

        // when
        runConcurrently(requests);

        // then: 반환이 먼저면 돌려준 뒤 다시 가져오고, 되돌리기가 먼저면 반환을 거절한다. 어느 쪽이든 잔액은 결제 직후와 같다
        assertThat(balanceOf(BUYER_ID)).isEqualTo(15_000);
        assertThat(jdbcTemplate.queryForList("SELECT status FROM wallet_order_refund_revert", String.class))
                .hasSize(5)
                .containsOnly("REVERTED");
    }

    @Test
    void 같은_구매자에게_되돌리기와_충전과_결제와_반환이_동시에_와도_잔액과_원장이_맞는다() throws Exception {
        // given: 1,000원씩 다섯 번 돌려받아 잔액 20,000원
        paidOrder();
        for (long refundId = 1; refundId <= 5; refundId++) {
            buyerWalletService.refundForOrder(refundId, ORDER_ID, BUYER_ID, 1_000);
        }
        List<Callable<Object>> requests = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            long revertRefundId = i;
            long newRefundId = 100L + i;
            String paymentKey = "pay-mixed-" + i;
            long orderId = ORDER_ID + i;
            requests.add(() -> buyerWalletService.revertRefundForOrder(revertRefundId, ORDER_ID, BUYER_ID, 1_000));
            requests.add(() -> buyerWalletService.refundForOrder(newRefundId, ORDER_ID, BUYER_ID, 1_000));
            requests.add(() -> {
                charger.charge(BUYER_ID, paymentKey, 1_000);
                return null;
            });
            requests.add(() -> buyerWalletService.payForOrder(BUYER_ID, orderId, 1_000));
        }

        // when
        runConcurrently(requests);

        // then: 되돌리기 -5,000, 반환 +5,000, 충전 +5,000, 결제 -5,000. 원장 이어짐과 짝은 정리 단계의 검사가 확인한다
        assertThat(balanceOf(BUYER_ID)).isEqualTo(20_000);
        assertThat(countLedgersByType("REFUND_REVERT")).isEqualTo(5);
    }

    @Test
    void 같은_주문의_되돌리기와_새_환불이_동시에_와도_결제_금액보다_많이_돌려주지_않는다() throws Exception {
        // given: 35,000원 결제한 주문에 10,000원씩 세 번 돌려줌
        paidOrder();
        for (long refundId = 1; refundId <= 3; refundId++) {
            buyerWalletService.refundForOrder(refundId, ORDER_ID, BUYER_ID, 10_000);
        }
        List<Callable<Object>> requests = new ArrayList<>();
        for (long refundId = 1; refundId <= 3; refundId++) {
            long id = refundId;
            requests.add(() -> buyerWalletService.revertRefundForOrder(id, ORDER_ID, BUYER_ID, 10_000));
        }
        for (long refundId = 4; refundId <= 8; refundId++) {
            long id = refundId;
            requests.add(() -> buyerWalletService.refundForOrder(id, ORDER_ID, BUYER_ID, 10_000));
        }

        // when
        runConcurrently(requests);

        // then: 어느 순서로 처리되든 다시 가져오지 않은 반환의 합은 결제 금액 이하다
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(r.amount), 0) FROM wallet_order_refund r
                WHERE r.order_id = ? AND r.status = 'COMPLETED'
                  AND NOT EXISTS (SELECT 1 FROM wallet_order_refund_revert v
                                  WHERE v.refund_id = r.refund_id AND v.status = 'REVERTED')
                """, Long.class, ORDER_ID)).isLessThanOrEqualTo(35_000L);
    }

    // 50,000원을 충전하고 35,000원을 결제한다 → 잔액 15,000원
    private void paidOrder() {
        charger.charge(BUYER_ID, "pay-" + BUYER_ID, 50_000);
        buyerWalletService.payForOrder(BUYER_ID, ORDER_ID, 35_000);
    }

    private void refundedOrder(Long refundId, long amount) {
        paidOrder();
        buyerWalletService.refundForOrder(refundId, ORDER_ID, BUYER_ID, amount);
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private long balanceOf(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM buyer_wallet WHERE member_id = ?", Long.class, memberId);
    }

    private long countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long countLedgersByReferenceKey(String referenceKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM buyer_wallet_ledger WHERE reference_key = ?", Long.class, referenceKey);
    }

    private long countLedgersByType(String type) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM buyer_wallet_ledger WHERE type = ?", Long.class, type);
    }

    // 상태|거절 사유
    private String refundRecord(Long refundId) {
        return jdbcTemplate.queryForObject(
                "SELECT CONCAT(status, '|', COALESCE(failure_reason, '')) FROM wallet_order_refund WHERE refund_id = ?",
                String.class, refundId);
    }

    // 유형|근거 키|금액|반영 뒤 잔액
    private String latestLedger(Long memberId) {
        return jdbcTemplate.queryForObject("""
                SELECT CONCAT(l.type, '|', l.reference_key, '|', l.amount, '|', l.balance_after)
                FROM buyer_wallet_ledger l
                JOIN buyer_wallet w ON l.buyer_wallet_id = w.id
                WHERE w.member_id = ?
                ORDER BY l.id DESC LIMIT 1
                """, String.class, memberId);
    }
}
