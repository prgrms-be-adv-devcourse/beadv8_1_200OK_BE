package com.ok.order.order.application.service;

import com.ok.order.OrderSchema;
import com.ok.order.cart.application.service.CartService;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderLine;
import com.ok.order.order.domain.SellerShippingFee;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 결제 완료와 결제 실패가 같은 주문에 동시에 처리될 때, 늦게 온 쪽이 앞의 결과를 덮어쓰지 않는지 확인한다(#398).
// 먼저 온 처리를 바깥 트랜잭션으로 감싸 커밋을 잠시 늦추고, 그 사이에 늦게 온 처리를 시작해 두 처리가 반드시 겹치게 한다.
// 잠금이 없으면 늦게 온 쪽이 커밋 전의 결제 대기를 읽고 자기 처리(재고 해제 신호, 장바구니 정리)를 끝낸 뒤,
// 먼저 온 쪽의 커밋이 상태를 덮어써 최종 상태와 이미 일어난 처리가 어긋난다.
class OrderServicePaymentConcurrencyIntegrationTest extends AbstractIntegrationTest {

    // 다른 테스트와 데이터가 겹치지 않게 이 클래스 전용 번호를 쓴다
    private static final Long BUYER_ID = 9_398_001L;
    private static final Long SELLER_ID = 9_398_901L;
    private static final Long OPTION_ID = 9_398_601L;
    // 늦게 온 처리가 잠금 대기(또는 잠금 없는 조회)에 들어갈 때까지 먼저 온 처리의 커밋을 미루는 시간
    private static final long OVERLAP_MILLIS = 1_000L;

    @Autowired
    OrderService sut;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CartService cartService;

    private Long orderId;

    // 결제 완료 처리는 주문한 상품을 장바구니에서 지운다. 늦게 온 결제 완료가 실제로 실행됐는지를 장바구니로 확인한다
    @BeforeEach
    void setUp() {
        cartService.addItem(BUYER_ID, OPTION_ID, 2);
        orderId = sut.create(paymentPendingOrder());
    }

    // 통합 테스트는 롤백되지 않아 이 클래스가 만든 주문·장바구니를 지운다
    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE ci FROM " + OrderSchema.NAME + ".cart_item ci JOIN " + OrderSchema.NAME
                + ".cart c ON ci.cart_id = c.id WHERE c.member_id = ?", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".cart WHERE member_id = ?", BUYER_ID);
        String orders = "SELECT id FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?";
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_third_party_consent WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE oi FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id IN (" + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_seller_group WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?", BUYER_ID);
    }

    @Test
    void 결제_완료_처리_중에_결제_실패가_오면_실패는_무시되고_결제_완료가_남는다() throws Exception {
        // when
        Future<Boolean> lateFail = runWhileHolding(() -> sut.completePayment(orderId), () -> sut.failPayment(orderId));

        // then
        boolean shouldReleaseStock = lateFail.get(30, TimeUnit.SECONDS);
        assertSoftly(softly -> {
            softly.assertThat(orderStatus()).isEqualTo("PAID");
            softly.assertThat(shouldReleaseStock).isFalse();
        });
    }

    @Test
    void 결제_실패_처리_중에_결제_완료가_오면_완료는_아무것도_하지_않고_결제_실패가_남는다() throws Exception {
        // when
        Future<Void> lateComplete = runWhileHolding(() -> sut.failPayment(orderId), () -> {
            sut.completePayment(orderId);
            return null;
        });

        // then
        lateComplete.get(30, TimeUnit.SECONDS);
        assertSoftly(softly -> {
            softly.assertThat(orderStatus()).isEqualTo("PAYMENT_FAILED");
            softly.assertThat(cartItemCount()).isEqualTo(1);
        });
    }

    // first를 바깥 트랜잭션 안에서 실행해 커밋을 미루고, 그동안 late를 다른 스레드에서 시작한다.
    // first가 커밋된 뒤 late의 결과를 돌려준다
    private <T> Future<T> runWhileHolding(Runnable first, Callable<T> late) throws Exception {
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch lateStarted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> firstDone = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                first.run();
                firstApplied.countDown();
                awaitQuietly(lateStarted);
                sleepQuietly(OVERLAP_MILLIS);
            }));
            assertThat(firstApplied.await(30, TimeUnit.SECONDS)).isTrue();
            Future<T> lateDone = executor.submit(() -> {
                lateStarted.countDown();
                return late.call();
            });
            firstDone.get(30, TimeUnit.SECONDS);
            lateDone.get(30, TimeUnit.SECONDS);
            return lateDone;
        }
    }

    private String orderStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM " + OrderSchema.NAME + ".orders WHERE id = ?", String.class, orderId);
    }

    private Integer cartItemCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + OrderSchema.NAME + ".cart_item ci JOIN "
                + OrderSchema.NAME + ".cart c ON ci.cart_id = c.id WHERE c.member_id = ?", Integer.class, BUYER_ID);
    }

    private static Order paymentPendingOrder() {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderLine(9_398_501L, OPTION_ID, SELLER_ID, "사과", "1kg", 10_000L, 2)));
        order.applyShippingFee(SELLER_ID, new SellerShippingFee(3_000L, null, 3_000L, 0L));
        order.assignIdempotencyKey("key-398-" + System.nanoTime());
        order.registerShippingAddress("홍길동", "010-1234-5678", "06236", "서울 강남구 테헤란로 1", "101호", null);
        order.agreeThirdPartyProvision(true);
        return order;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
