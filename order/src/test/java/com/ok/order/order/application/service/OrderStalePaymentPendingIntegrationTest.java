package com.ok.order.order.application.service;

import com.ok.order.OrderSchema;
import com.ok.order.cart.application.service.CartService;
import com.ok.order.order.adapter.in.scheduler.StalePaymentPendingOrderScheduler;
import com.ok.order.order.application.port.in.CancelStalePaymentPendingOrderUseCase;
import com.ok.order.order.application.port.in.FailPaymentUseCase;
import com.ok.order.order.application.port.in.RequestPaymentExpireUseCase;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderLine;
import com.ok.order.order.domain.SellerShippingFee;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeProductPort;
import com.ok.testsupport.OrderKafkaTopicReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 결제 대기 정리(#402)와 결제 만료 요청(#656)을 실제 DB·Kafka로 확인한다.
// - 결제 요청 전에 멈춘 주문(5분)은 취소하고, 결제를 요청한 주문(30분)은 취소하지 않고 지갑에 결제 만료를 요청한다
// - 정리와 결제 완료가 같은 주문에 동시에 와도 한쪽만 반영된다
class OrderStalePaymentPendingIntegrationTest extends AbstractIntegrationTest {

    // 다른 테스트와 데이터가 겹치지 않게 이 클래스 전용 번호를 쓴다
    private static final Long BUYER_ID = 9_402_001L;
    private static final Long SELLER_ID = 9_402_901L;
    private static final Long OPTION_ID = 9_402_601L;
    // 늦게 온 처리가 잠금 대기에 들어갈 때까지 먼저 온 처리의 커밋을 미루는 시간
    private static final long OVERLAP_MILLIS = 1_000L;
    private static final String EXPIRE_TOPIC = "order.order-payment-expire-requested.v1";
    // 다시 보낸 중복까지 세려고 이 시간 동안 계속 읽는다. 커밋 직후 보내므로 정상이면 바로 읽힌다
    private static final Duration READ_DURATION = Duration.ofSeconds(3);

    @Autowired
    CancelStalePaymentPendingOrderUseCase sut;

    @Autowired
    RequestPaymentExpireUseCase requestPaymentExpire;

    @Autowired
    FailPaymentUseCase failPayment;

    @Autowired
    StalePaymentPendingOrderScheduler scheduler;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    @Autowired
    OrderService orderService;

    @Autowired
    CartService cartService;

    @Autowired
    FakeProductPort fakeProductPort;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Clock clock;

    private OrderKafkaTopicReader expireReader;

    @BeforeEach
    void setUp() {
        fakeProductPort.clear();
        expireReader = new OrderKafkaTopicReader(kafkaConnectionDetails.getBootstrapServers(), EXPIRE_TOPIC);
    }

    // 통합 테스트는 롤백되지 않아 이 클래스가 만든 주문·장바구니를 지운다
    @AfterEach
    void tearDown() {
        expireReader.close();
        fakeProductPort.clear();
        jdbcTemplate.update("DELETE ci FROM " + OrderSchema.NAME + ".cart_item ci JOIN " + OrderSchema.NAME
                + ".cart c ON ci.cart_id = c.id WHERE c.member_id = ?", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".cart WHERE member_id = ?", BUYER_ID);
        String orders = "SELECT id FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?";
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_outbox WHERE message_key IN ("
                + "SELECT CAST(id AS CHAR) FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?)", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_third_party_consent WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE oi FROM " + OrderSchema.NAME + ".order_item oi JOIN " + OrderSchema.NAME
                + ".order_seller_group g ON oi.seller_group_id = g.id WHERE g.order_id IN (" + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_seller_group WHERE order_id IN ("
                + orders + ")", BUYER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".orders WHERE buyer_id = ?", BUYER_ID);
    }

    @Test
    void 결제_요청_전에_5분_넘게_멈춘_주문은_정리_대상이고_요청_뒤_30분이_지난_주문은_만료_요청_대상이다() {
        // given
        LocalDateTime now = LocalDateTime.now(clock);
        Long creatingStale = pendingOrder(now.minusMinutes(6), null);
        Long creatingFresh = pendingOrder(now.minusMinutes(4), null);
        Long requestedStale = pendingOrder(now.minusHours(1), now.minusMinutes(31));
        Long requestedFresh = pendingOrder(now.minusHours(1), now.minusMinutes(29));
        Long paid = pendingOrder(now.minusHours(1), now.minusHours(1));
        orderService.completePayment(paid);

        // when
        List<Long> staleOrderIds = sut.findStalePaymentPendingOrderIds();
        List<Long> expireDueOrderIds = requestPaymentExpire.findPaymentExpireDueOrderIds();

        // then: 다른 테스트가 남긴 주문이 섞일 수 있어 이 테스트의 주문만 확인한다
        assertSoftly(softly -> {
            softly.assertThat(staleOrderIds).contains(creatingStale);
            softly.assertThat(staleOrderIds).doesNotContain(creatingFresh, requestedStale, requestedFresh, paid);
            softly.assertThat(expireDueOrderIds).contains(requestedStale);
            softly.assertThat(expireDueOrderIds).doesNotContain(creatingStale, creatingFresh, requestedFresh, paid);
        });
    }

    @Test
    void 결제_요청_전에_멈춘_주문을_정리하면_취소되고_멱등키를_비우고_재고를_해제한다() {
        // given
        LocalDateTime now = LocalDateTime.now(clock);
        Long orderId = pendingOrder(now.minusMinutes(6), null);

        // when
        boolean canceled = sut.cancelStalePaymentPendingOrder(orderId);

        // then
        assertSoftly(softly -> {
            softly.assertThat(canceled).isTrue();
            softly.assertThat(orderStatus(orderId)).isEqualTo("CANCELED");
            softly.assertThat(idempotencyKey(orderId)).isNull();
            softly.assertThat(fakeProductPort.releasedOrderIds()).containsExactly(orderId);
        });
    }

    @Test
    void 정리_중에_결제_완료가_오면_완료는_아무것도_하지_않고_취소가_남는다() throws Exception {
        // given
        cartService.addItem(BUYER_ID, OPTION_ID, 2);
        LocalDateTime now = LocalDateTime.now(clock);
        Long orderId = pendingOrder(now.minusMinutes(6), null);

        // when
        Future<Void> lateComplete = runWhileHolding(() -> orderService.cancelStalePaymentPending(orderId), () -> {
            orderService.completePayment(orderId);
            return null;
        });

        // then
        lateComplete.get(30, TimeUnit.SECONDS);
        assertSoftly(softly -> {
            softly.assertThat(orderStatus(orderId)).isEqualTo("CANCELED");
            softly.assertThat(cartItemCount()).isEqualTo(1);
        });
    }

    @Test
    void 결제_완료_처리_중에_정리가_오면_정리는_취소하지_않고_결제_완료가_남는다() throws Exception {
        // given
        LocalDateTime now = LocalDateTime.now(clock);
        Long orderId = pendingOrder(now.minusMinutes(6), null);

        // when
        Future<Boolean> lateCancel = runWhileHolding(() -> orderService.completePayment(orderId),
                () -> orderService.cancelStalePaymentPending(orderId));

        // then
        boolean shouldReleaseStock = lateCancel.get(30, TimeUnit.SECONDS);
        assertSoftly(softly -> {
            softly.assertThat(orderStatus(orderId)).isEqualTo("PAID");
            softly.assertThat(shouldReleaseStock).isFalse();
        });
    }

    @Test
    void 정리_스케줄러는_결제를_요청한_주문을_취소하지_않고_지갑에_결제_만료를_요청한다() {
        // given
        LocalDateTime now = LocalDateTime.now(clock);
        Long orderId = pendingOrder(now.minusHours(1), now.minusMinutes(31));

        // when
        scheduler.run();

        // then: 주문과 재고는 그대로이고, 만료 요청이 키 orderId로 나간다. 금액은 상품 20,000원 + 배송비 3,000원
        List<String> messages = expireReader.readValuesFor(String.valueOf(orderId), READ_DURATION);
        assertSoftly(softly -> {
            softly.assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_PENDING");
            softly.assertThat(fakeProductPort.releasedOrderIds()).doesNotContain(orderId);
            softly.assertThat(messages).containsExactly(
                    "{\"orderId\":" + orderId + ",\"buyerId\":" + BUYER_ID + ",\"totalAmount\":23000}");
        });
    }

    @Test
    void 결과가_올_때까지_만료_요청을_다시_보내도_주문은_바뀌지_않는다() {
        // given
        LocalDateTime now = LocalDateTime.now(clock);
        Long orderId = pendingOrder(now.minusHours(1), now.minusMinutes(31));

        // when: 스케줄러가 두 번 돌았다
        boolean first = requestPaymentExpire.requestPaymentExpire(orderId);
        boolean second = requestPaymentExpire.requestPaymentExpire(orderId);

        // then
        assertSoftly(softly -> {
            softly.assertThat(first).isTrue();
            softly.assertThat(second).isTrue();
            softly.assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_PENDING");
            softly.assertThat(expireReader.readValuesFor(String.valueOf(orderId), READ_DURATION)).hasSize(2);
        });
    }

    @Test
    void 만료_요청_뒤_지갑이_결제_완료를_보내면_결제_완료되고_더는_만료를_요청하지_않는다() {
        // given: 지갑이 이미 결제했던 주문이라 만료 요청에 결제 완료를 다시 보냈다
        LocalDateTime now = LocalDateTime.now(clock);
        Long orderId = pendingOrder(now.minusHours(1), now.minusMinutes(31));
        requestPaymentExpire.requestPaymentExpire(orderId);

        // when
        orderService.completePayment(orderId);

        // then
        assertSoftly(softly -> {
            softly.assertThat(orderStatus(orderId)).isEqualTo("PAID");
            softly.assertThat(requestPaymentExpire.findPaymentExpireDueOrderIds()).doesNotContain(orderId);
            softly.assertThat(requestPaymentExpire.requestPaymentExpire(orderId)).isFalse();
        });
    }

    @Test
    void 만료_요청_뒤_지갑이_결제_만료_실패를_보내면_결제_실패로_끝나고_재고를_해제한다() {
        // given
        LocalDateTime now = LocalDateTime.now(clock);
        Long orderId = pendingOrder(now.minusHours(1), now.minusMinutes(31));
        requestPaymentExpire.requestPaymentExpire(orderId);

        // when
        failPayment.failPayment(orderId, "PAYMENT_EXPIRED");

        // then
        assertSoftly(softly -> {
            softly.assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_FAILED");
            softly.assertThat(fakeProductPort.releasedOrderIds()).containsExactly(orderId);
            softly.assertThat(requestPaymentExpire.findPaymentExpireDueOrderIds()).doesNotContain(orderId);
        });
    }

    // 결제 대기 주문을 저장하고, 생성 시각과 결제 요청 시각을 원하는 과거로 옮긴다(결제 요청 시각이 null이면 요청 전)
    private Long pendingOrder(LocalDateTime createdAt, LocalDateTime paymentRequestedAt) {
        Long orderId = orderService.create(paymentPendingOrder());
        jdbcTemplate.update("UPDATE " + OrderSchema.NAME + ".orders SET created_at = ?, payment_requested_at = ? WHERE id = ?",
                createdAt, paymentRequestedAt, orderId);
        return orderId;
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

    private String orderStatus(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM " + OrderSchema.NAME + ".orders WHERE id = ?", String.class, orderId);
    }

    private String idempotencyKey(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM " + OrderSchema.NAME + ".orders WHERE id = ?", String.class, orderId);
    }

    private Integer cartItemCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + OrderSchema.NAME + ".cart_item ci JOIN "
                + OrderSchema.NAME + ".cart c ON ci.cart_id = c.id WHERE c.member_id = ?", Integer.class, BUYER_ID);
    }

    private static Order paymentPendingOrder() {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderLine(9_402_501L, OPTION_ID, SELLER_ID, "사과", "1kg", 10_000L, 2)));
        order.applyShippingFee(SELLER_ID, new SellerShippingFee(3_000L, null, 3_000L, 0L));
        order.assignIdempotencyKey("key-402-" + System.nanoTime());
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
