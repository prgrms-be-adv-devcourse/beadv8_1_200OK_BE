package com.ok.wallet.application.service;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.application.dto.OrderRefundResult;
import com.ok.wallet.domain.WalletOrderRefundFailureReason;
import com.ok.wallet.domain.WalletOrderRefundStatus;
import com.ok.wallet.support.WalletTestDataCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

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

// 환불 반환 처리(#34). 동시 요청에서 각 트랜잭션이 실제로 커밋되어야 하므로 테스트에 @Transactional을 붙이지 않는다
class BuyerWalletRefundIntegrationTest extends AbstractIntegrationTest {
    private static final Long BUYER_ID = 7_001L;
    private static final Long ORDER_ID = 70_001L;

    @Autowired
    BuyerWalletService buyerWalletService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        new WalletTestDataCleaner(jdbcTemplate).deleteAll();
    }

    @AfterEach
    void tearDown() {
        new WalletTestDataCleaner(jdbcTemplate).verifyAndDeleteAll();
    }

    @Test
    void 결제한_주문의_환불_금액을_돌려주고_원장에_남긴다() {
        // given: 50,000원 충전 뒤 35,000원 결제 → 잔액 15,000원
        paidOrder(BUYER_ID, ORDER_ID, 35_000);

        // when
        OrderRefundResult sut = buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(WalletOrderRefundStatus.COMPLETED);
            softly.assertThat(sut.failureReason()).isNull();
            softly.assertThat(sut.amount()).isEqualTo(7_000);
            softly.assertThat(sut.alreadyProcessed()).isFalse();
            softly.assertThat(balanceOf(BUYER_ID)).isEqualTo(22_000);
            softly.assertThat(latestLedger(BUYER_ID)).isEqualTo("REFUND|REFUND:1|7000|22000");
        });
    }

    @Test
    void 결제_기록이_없는_주문은_돌려주지_않고_결제_없음으로_거절한다() {
        // given
        buyerWalletService.chargeByPayment(BUYER_ID, "pay-1", 10_000);

        // when
        OrderRefundResult sut = buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // then
        assertRejected(sut, WalletOrderRefundFailureReason.PAYMENT_NOT_FOUND);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(10_000);
    }

    @Test
    void 잔액_부족으로_결제에_실패한_주문은_결제_없음으로_거절한다() {
        // given
        buyerWalletService.chargeByPayment(BUYER_ID, "pay-1", 1_000);
        buyerWalletService.payForOrder(BUYER_ID, ORDER_ID, 35_000);

        // when
        OrderRefundResult sut = buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // then
        assertRejected(sut, WalletOrderRefundFailureReason.PAYMENT_NOT_FOUND);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(1_000);
    }

    @Test
    void 결제한_구매자가_아니면_결제_없음으로_거절하고_어느_지갑에도_돌려주지_않는다() {
        // given
        Long otherBuyerId = 7_002L;
        paidOrder(BUYER_ID, ORDER_ID, 35_000);
        buyerWalletService.chargeByPayment(otherBuyerId, "pay-other", 1_000);

        // when
        OrderRefundResult sut = buyerWalletService.refundForOrder(1L, ORDER_ID, otherBuyerId, 7_000);

        // then
        assertRejected(sut, WalletOrderRefundFailureReason.PAYMENT_NOT_FOUND);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(15_000);
        assertThat(balanceOf(otherBuyerId)).isEqualTo(1_000);
    }

    @Test
    void 나눠_환불해도_합계가_결제_금액까지는_모두_돌려준다() {
        // given: 감자 10,000원 + 배추 25,000원, 무료배송으로 35,000원 결제(#528 예시)
        paidOrder(BUYER_ID, ORDER_ID, 35_000);

        // when: 감자는 배송비 3,000원을 빼고, 배추는 뺐던 배송비를 돌려받는다
        OrderRefundResult potato = buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);
        OrderRefundResult cabbage = buyerWalletService.refundForOrder(2L, ORDER_ID, BUYER_ID, 28_000);

        // then
        assertThat(List.of(potato.status(), cabbage.status()))
                .containsOnly(WalletOrderRefundStatus.COMPLETED);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(50_000);
    }

    @Test
    void 반환_합계가_결제_금액을_넘으면_돌려주지_않고_합계_초과로_거절한다() {
        // given: 35,000원 결제 중 30,000원을 이미 돌려줌
        paidOrder(BUYER_ID, ORDER_ID, 35_000);
        buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 30_000);

        // when: 남은 5,000원보다 많은 6,000원을 요청
        OrderRefundResult sut = buyerWalletService.refundForOrder(2L, ORDER_ID, BUYER_ID, 6_000);

        // then
        assertRejected(sut, WalletOrderRefundFailureReason.REFUND_TOTAL_EXCEEDED);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(45_000);
    }

    @Test
    void 거절한_환불은_합계에_세지_않는다() {
        // given: 6,000원 요청은 거절됨
        paidOrder(BUYER_ID, ORDER_ID, 35_000);
        buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 30_000);
        buyerWalletService.refundForOrder(2L, ORDER_ID, BUYER_ID, 6_000);

        // when: 남은 5,000원을 요청
        OrderRefundResult sut = buyerWalletService.refundForOrder(3L, ORDER_ID, BUYER_ID, 5_000);

        // then
        assertThat(sut.status()).isEqualTo(WalletOrderRefundStatus.COMPLETED);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(50_000);
    }

    @Test
    void 같은_환불이_다시_오면_돌려주지_않고_처음_결과를_돌려준다() {
        // given
        paidOrder(BUYER_ID, ORDER_ID, 35_000);
        buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // when
        OrderRefundResult sut = buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(WalletOrderRefundStatus.COMPLETED);
            softly.assertThat(sut.alreadyProcessed()).isTrue();
            softly.assertThat(balanceOf(BUYER_ID)).isEqualTo(22_000);
            softly.assertThat(countLedgers(BUYER_ID)).isEqualTo(3);
        });
    }

    @Test
    void 같은_환불이_다른_금액으로_다시_와도_돌려주지_않고_처음_금액을_돌려준다() {
        // given
        paidOrder(BUYER_ID, ORDER_ID, 35_000);
        buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 7_000);

        // when
        OrderRefundResult sut = buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 9_000);

        // then: 처음 금액이 나가야 환불이 금액이 다른 것을 알아챈다
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(WalletOrderRefundStatus.COMPLETED);
            softly.assertThat(sut.amount()).isEqualTo(7_000);
            softly.assertThat(sut.alreadyProcessed()).isTrue();
            softly.assertThat(balanceOf(BUYER_ID)).isEqualTo(22_000);
        });
    }

    @Test
    void 거절한_환불이_다시_와도_처음_거절을_돌려준다() {
        // given
        paidOrder(BUYER_ID, ORDER_ID, 35_000);
        buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 40_000);

        // when
        OrderRefundResult sut = buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 40_000);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(WalletOrderRefundStatus.FAILED);
            softly.assertThat(sut.failureReason()).isEqualTo(WalletOrderRefundFailureReason.REFUND_TOTAL_EXCEEDED);
            softly.assertThat(sut.alreadyProcessed()).isTrue();
        });
    }

    @Test
    void 보유_한도를_넘어도_돌려준다() {
        // given: 100,000원 결제 뒤 다시 충전해 잔액이 한도(500,000원)에 닿음
        buyerWalletService.chargeByPayment(BUYER_ID, "pay-1", 500_000);
        buyerWalletService.payForOrder(BUYER_ID, ORDER_ID, 100_000);
        buyerWalletService.chargeByPayment(BUYER_ID, "pay-2", 100_000);

        // when
        OrderRefundResult sut = buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 100_000);

        // then
        assertThat(sut.status()).isEqualTo(WalletOrderRefundStatus.COMPLETED);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(600_000);
    }

    @ParameterizedTest
    @MethodSource("잘못된_반환_요청")
    void 값이_잘못된_요청은_기록하지_않고_예외가_난다(Long refundId, Long orderId, Long memberId, long amount,
                                                String message) {
        // when & then
        assertThatThrownBy(() -> buyerWalletService.refundForOrder(refundId, orderId, memberId, amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_order_refund", Long.class)).isZero();
    }

    static Stream<Arguments> 잘못된_반환_요청() {
        return Stream.of(
                Arguments.of(null, 1L, 1L, 1_000L, "환불 ID, 주문 ID, 회원 ID는 필수입니다. refundId=null, orderId=1, memberId=1"),
                Arguments.of(1L, null, 1L, 1_000L, "환불 ID, 주문 ID, 회원 ID는 필수입니다. refundId=1, orderId=null, memberId=1"),
                Arguments.of(1L, 1L, null, 1_000L, "환불 ID, 주문 ID, 회원 ID는 필수입니다. refundId=1, orderId=1, memberId=null"),
                Arguments.of(1L, 1L, 1L, 0L, "반환 금액은 0보다 커야 합니다. amount=0"),
                Arguments.of(1L, 1L, 1L, -1L, "반환 금액은 0보다 커야 합니다. amount=-1"));
    }

    @Test
    void 같은_환불이_동시에_와도_한_번만_돌려준다() throws Exception {
        // given
        paidOrder(BUYER_ID, ORDER_ID, 35_000);
        List<Callable<OrderRefundResult>> refunds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            refunds.add(() -> buyerWalletService.refundForOrder(1L, ORDER_ID, BUYER_ID, 7_000));
        }

        // when
        List<OrderRefundResult> sut = runConcurrently(refunds);

        // then
        assertThat(sut).filteredOn(result -> !result.alreadyProcessed()).hasSize(1);
        assertThat(sut).extracting(OrderRefundResult::status).containsOnly(WalletOrderRefundStatus.COMPLETED);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(22_000);
    }

    @Test
    void 같은_주문의_다른_환불이_동시에_와도_결제_금액보다_많이_돌려주지_않는다() throws Exception {
        // given: 35,000원 결제한 주문에 10,000원씩 다섯 건이 동시에 옴
        paidOrder(BUYER_ID, ORDER_ID, 35_000);
        List<Callable<OrderRefundResult>> refunds = new ArrayList<>();
        for (long refundId = 1; refundId <= 5; refundId++) {
            long id = refundId;
            refunds.add(() -> buyerWalletService.refundForOrder(id, ORDER_ID, BUYER_ID, 10_000));
        }

        // when
        List<OrderRefundResult> sut = runConcurrently(refunds);

        // then: 세 건만 돌려주고 나머지는 합계 초과로 거절한다
        assertThat(sut).filteredOn(OrderRefundResult::completed).hasSize(3);
        assertThat(sut).filteredOn(result -> !result.completed())
                .extracting(OrderRefundResult::failureReason)
                .containsOnly(WalletOrderRefundFailureReason.REFUND_TOTAL_EXCEEDED);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(45_000);
    }

    @Test
    void 이미_열린_트랜잭션_안에서_같은_주문의_다른_환불이_동시에_와도_결제_금액보다_많이_돌려주지_않는다() throws Exception {
        // given: 합류하면 READ COMMITTED가 무시되고 기본 격리 수준(REPEATABLE READ)의 스냅샷을 쓴다(#360과 같은 상황)
        paidOrder(BUYER_ID, ORDER_ID, 35_000);
        List<Callable<OrderRefundResult>> refunds = new ArrayList<>();
        for (long refundId = 1; refundId <= 5; refundId++) {
            long id = refundId;
            refunds.add(() -> transactionTemplate.execute(
                    status -> buyerWalletService.refundForOrder(id, ORDER_ID, BUYER_ID, 10_000)));
        }

        // when
        List<OrderRefundResult> sut = runConcurrently(refunds);

        // then: 합계를 잠금 조회로 읽어 스냅샷과 상관없이 방금 끝난 반환까지 센다
        assertThat(sut).filteredOn(OrderRefundResult::completed).hasSize(3);
        assertThat(balanceOf(BUYER_ID)).isEqualTo(45_000);
    }

    @Test
    void 주문_번호가_이웃한_여러_구매자의_첫_환불이_동시에_와도_교착_없이_모두_돌려준다() throws Exception {
        // given: 구매자마다 주문 하나를 결제함. 반환 기록이 아직 없어 합계 조회가 빈 구간을 보는 경우다
        int buyerCount = 10;
        List<Callable<OrderRefundResult>> refunds = new ArrayList<>();
        for (int i = 0; i < buyerCount; i++) {
            Long buyerId = 7_100L + i;
            Long orderId = 71_000L + i;
            paidOrder(buyerId, orderId, 35_000);
            long refundId = 100L + i;
            refunds.add(() -> buyerWalletService.refundForOrder(refundId, orderId, buyerId, 7_000));
        }

        // when: 교착이 나면 한쪽이 예외로 끝나 runConcurrently가 실패한다
        List<OrderRefundResult> sut = runConcurrently(refunds);

        // then
        assertThat(sut).extracting(OrderRefundResult::status).containsOnly(WalletOrderRefundStatus.COMPLETED);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_order_refund", Long.class))
                .isEqualTo(buyerCount);
    }

    private void assertRejected(OrderRefundResult sut, WalletOrderRefundFailureReason reason) {
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(WalletOrderRefundStatus.FAILED);
            softly.assertThat(sut.failureReason()).isEqualTo(reason);
            softly.assertThat(sut.alreadyProcessed()).isFalse();
            softly.assertThat(countLedgersByReferenceKey("REFUND:" + sut.refundId())).isZero();
        });
    }

    // 50,000원을 충전하고 주문을 결제한다. 충전 건 없이 충전 원장만 만드는 것은 정리 도우미가 허용한다(한 방향 검사)
    private void paidOrder(Long buyerId, Long orderId, long amount) {
        buyerWalletService.chargeByPayment(buyerId, "pay-" + buyerId + "-" + orderId, 50_000);
        buyerWalletService.payForOrder(buyerId, orderId, amount);
    }

    private List<OrderRefundResult> runConcurrently(List<Callable<OrderRefundResult>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderRefundResult>> futures = new ArrayList<>();
        try {
            for (Callable<OrderRefundResult> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();
            List<OrderRefundResult> results = new ArrayList<>();
            for (Future<OrderRefundResult> future : futures) {
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

    private long countLedgers(Long memberId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM buyer_wallet_ledger l
                JOIN buyer_wallet w ON l.buyer_wallet_id = w.id
                WHERE w.member_id = ?
                """, Long.class, memberId);
    }

    private long countLedgersByReferenceKey(String referenceKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM buyer_wallet_ledger WHERE reference_key = ?", Long.class, referenceKey);
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
