package com.ok.order.order.application.service;

import com.ok.order.OrderSchema;
import com.ok.order.order.adapter.in.scheduler.StalePaymentPendingOrderScheduler;
import com.ok.order.order.application.port.in.RetryStockReleaseUseCase;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderLine;
import com.ok.order.order.domain.SellerShippingFee;
import com.ok.order.order.exception.OrderErrorCode;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeProductPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 재고 해제 재처리(#657): 해제를 확인하지 못한 취소·결제 실패 주문을 실제 DB 조회로 고르고, 다시 풀면 해제 확인이 남는지 본다
class OrderStockReleaseRetryIntegrationTest extends AbstractIntegrationTest {

    // 다른 테스트와 데이터가 겹치지 않게 이 클래스 전용 번호를 쓴다
    private static final Long BUYER_ID = 9_657_001L;
    private static final Long SELLER_ID = 9_657_901L;
    private static final Long OPTION_ID = 9_657_601L;

    @Autowired
    RetryStockReleaseUseCase sut;

    @Autowired
    StalePaymentPendingOrderScheduler scheduler;

    @Autowired
    OrderService orderService;

    @Autowired
    FakeProductPort fakeProductPort;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Clock clock;

    @BeforeEach
    void setUp() {
        fakeProductPort.clear();
    }

    // 통합 테스트는 롤백되지 않아 이 클래스가 만든 주문을 지운다
    @AfterEach
    void tearDown() {
        fakeProductPort.clear();
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
    void 해제를_확인하지_못하고_1분이_지난_취소_결제_실패_주문만_재처리_대상이다() {
        // given
        Long canceled = canceledOrderChangedMinutesAgo(2);
        Long paymentFailed = paymentFailedOrderChangedMinutesAgo(2);
        Long justCanceled = canceledOrderChangedMinutesAgo(0);
        Long released = canceledOrderChangedMinutesAgo(2);
        orderService.markStockReleased(released);
        changedMinutesAgo(released, 2);
        Long paid = savedOrder();
        orderService.completePayment(paid);
        changedMinutesAgo(paid, 2);

        // when
        List<Long> pendingOrderIds = sut.findStockReleasePendingOrderIds();

        // then
        assertThat(pendingOrderIds).contains(canceled, paymentFailed).doesNotContain(justCanceled, released, paid);
    }

    @Test
    void 재처리로_재고를_풀면_해제_확인이_남고_다음_대상에서_빠진다() {
        // given
        Long orderId = canceledOrderChangedMinutesAgo(2);

        // when
        sut.retryStockRelease(orderId);

        // then
        assertSoftly(softly -> {
            softly.assertThat(fakeProductPort.releasedOrderIds()).containsExactly(orderId);
            softly.assertThat(stockReleasedAt(orderId)).isNotNull();
            softly.assertThat(sut.findStockReleasePendingOrderIds()).doesNotContain(orderId);
        });
    }

    @Test
    void 재처리에서도_해제가_실패하면_해제_확인은_비어_있고_다음_대상으로_남는다() {
        // given
        Long orderId = canceledOrderChangedMinutesAgo(2);
        fakeProductPort.failReleaseWith(OrderErrorCode.PRODUCT_API_FAILED);

        // when
        sut.retryStockRelease(orderId);

        // then
        assertSoftly(softly -> {
            softly.assertThat(stockReleasedAt(orderId)).isNull();
            softly.assertThat(sut.findStockReleasePendingOrderIds()).contains(orderId);
        });
    }

    @Test
    void 정리할_결제_대기_주문이_없어도_스케줄러가_재고_해제를_다시_부른다() {
        // given
        Long orderId = canceledOrderChangedMinutesAgo(2);

        // when
        scheduler.run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(fakeProductPort.releasedOrderIds()).contains(orderId);
            softly.assertThat(stockReleasedAt(orderId)).isNotNull();
        });
    }

    @Test
    void 한_시간_넘게_해제를_확인하지_못한_주문만_사람_확인_대상이다() {
        // given
        Long stuck = canceledOrderChangedMinutesAgo(61);
        Long recent = canceledOrderChangedMinutesAgo(30);

        // when
        List<Long> stuckOrderIds = sut.findStuckStockReleaseOrderIds();

        // then
        assertThat(stuckOrderIds).contains(stuck).doesNotContain(recent);
    }

    private Long canceledOrderChangedMinutesAgo(int minutes) {
        Long orderId = savedOrder();
        orderService.cancel(orderId);
        changedMinutesAgo(orderId, minutes);
        return orderId;
    }

    private Long paymentFailedOrderChangedMinutesAgo(int minutes) {
        Long orderId = savedOrder();
        orderService.failPayment(orderId);
        changedMinutesAgo(orderId, minutes);
        return orderId;
    }

    // 상태가 바뀐 시각(updated_at)을 앞당겨 재처리 대기 시간이 지난 주문을 만든다
    private void changedMinutesAgo(Long orderId, int minutes) {
        jdbcTemplate.update("UPDATE " + OrderSchema.NAME + ".orders SET updated_at = ? WHERE id = ?",
                LocalDateTime.now(clock).minusMinutes(minutes), orderId);
    }

    private LocalDateTime stockReleasedAt(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT stock_released_at FROM " + OrderSchema.NAME + ".orders WHERE id = ?",
                LocalDateTime.class, orderId);
    }

    private Long savedOrder() {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderLine(9_657_501L, OPTION_ID, SELLER_ID, "감자", "1kg", 10_000L, 1)));
        order.applyShippingFee(SELLER_ID, new SellerShippingFee(3_000L, null, 3_000L, 0L));
        order.assignIdempotencyKey("key-657-" + System.nanoTime());
        order.registerShippingAddress("홍길동", "010-1234-5678", "06236", "서울 강남구 테헤란로 1", "101호", null);
        order.agreeThirdPartyProvision(true);
        return orderService.create(order);
    }
}
