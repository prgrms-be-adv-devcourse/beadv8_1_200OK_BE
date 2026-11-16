package com.ok.order.order.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.order.OrderSchema;
import com.ok.order.order.application.dto.RecordOrderItemRefundCommand;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderLine;
import com.ok.order.order.domain.SellerShippingFee;
import com.ok.order.order.exception.OrderErrorCode;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 배송 시작과 환불 기록이 같은 주문에 동시에 처리될 때, 출고된 상품에 환불이 기록되지 않는지 확인한다(#658).
// 먼저 온 처리를 바깥 트랜잭션으로 감싸 커밋을 잠시 늦추고, 그 사이에 늦게 온 처리를 시작해 두 처리가 반드시 겹치게 한다.
// 배송 시작이 주문을 잠그지 않으면 환불은 커밋 전의 배송 준비를 보고 기록을 남기고, 그 뒤 배송 시작이 커밋되어
// 배송 중인 상품에 환불이 남는다
class OrderServiceShippingConcurrencyIntegrationTest extends AbstractIntegrationTest {

    // 다른 테스트와 데이터가 겹치지 않게 이 클래스 전용 번호를 쓴다
    private static final Long BUYER_ID = 9_658_001L;
    private static final Long SELLER_ID = 9_658_901L;
    private static final Long OPTION_ID = 9_658_601L;
    // 늦게 온 처리가 잠금 대기(또는 잠금 없는 조회)에 들어갈 때까지 먼저 온 처리의 커밋을 미루는 시간
    private static final long OVERLAP_MILLIS = 1_000L;

    @Autowired
    OrderService sut;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long orderId;
    private Long orderItemId;

    @BeforeEach
    void setUp() {
        orderId = sut.create(paymentPendingOrder());
        sut.completePayment(orderId);
        orderItemId = jdbcTemplate.queryForObject("SELECT oi.id FROM " + OrderSchema.NAME + ".order_item oi JOIN "
                + OrderSchema.NAME + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id = ?",
                Long.class, orderId);
    }

    // 통합 테스트는 롤백되지 않아 이 클래스가 만든 주문을 지운다
    @AfterEach
    void tearDown() {
        String orders = "SELECT id FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?";
        String items = "SELECT oi.id FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id IN (" + orders + ")";
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_item_refund WHERE order_item_id IN ("
                + items + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_third_party_consent WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE oi FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id IN (" + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_seller_group WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?", BUYER_ID);
    }

    @Test
    void 배송_시작_처리_중에_환불이_오면_환불은_출고_뒤라_거절되고_기록이_남지_않는다() throws Exception {
        // when
        Future<Void> lateRefund = runWhileHolding(() -> sut.startShipping(orderId, SELLER_ID), () -> {
            sut.recordRefund(new RecordOrderItemRefundCommand(orderItemId, 7L, 1, 0L));
            return null;
        });

        // then
        assertThatThrownBy(() -> lateRefund.get(30, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.REFUND_NOT_ALLOWED_STATUS));
        assertSoftly(softly -> {
            softly.assertThat(itemStatus()).isEqualTo("SHIPPING");
            softly.assertThat(refundCount()).isZero();
        });
    }

    @Test
    void 환불_기록_중에_배송이_시작되면_환불이_먼저_남고_상품은_배송_중이_된다() throws Exception {
        // when
        Future<Void> lateShipping = runWhileHolding(
                () -> sut.recordRefund(new RecordOrderItemRefundCommand(orderItemId, 7L, 1, 0L)), () -> {
                    sut.startShipping(orderId, SELLER_ID);
                    return null;
                });

        // then
        lateShipping.get(30, TimeUnit.SECONDS);
        assertSoftly(softly -> {
            softly.assertThat(itemStatus()).isEqualTo("SHIPPING");
            softly.assertThat(refundCount()).isEqualTo(1);
        });
    }

    // first를 바깥 트랜잭션 안에서 실행해 커밋을 미루고, 그동안 late를 다른 스레드에서 시작한다.
    // first가 커밋된 뒤 late의 결과를 돌려준다. late가 던진 예외는 돌려준 Future에서 꺼낸다
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
            return lateDone;
        }
    }

    private String itemStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM " + OrderSchema.NAME + ".order_item WHERE id = ?", String.class, orderItemId);
    }

    private Integer refundCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + OrderSchema.NAME
                + ".order_item_refund WHERE order_item_id = ?", Integer.class, orderItemId);
    }

    private static Order paymentPendingOrder() {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderLine(9_658_501L, OPTION_ID, SELLER_ID, "감자", "1kg", 10_000L, 3)));
        order.applyShippingFee(SELLER_ID, new SellerShippingFee(3_000L, null, 3_000L, 0L));
        order.assignIdempotencyKey("key-658-" + System.nanoTime());
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
