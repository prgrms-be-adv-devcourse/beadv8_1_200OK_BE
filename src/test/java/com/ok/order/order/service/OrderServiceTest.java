package com.ok.order.order.service;

import com.ok.common.exception.RestApiException;
import com.ok.order.cart.service.CartService;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderItem;
import com.ok.order.order.domain.OrderItemStatus;
import com.ok.order.order.domain.OrderLine;
import com.ok.order.order.domain.OrderStatus;
import com.ok.order.order.exception.OrderErrorCode;
import com.ok.order.order.repository.OrderRepository;
import com.ok.order.order.service.dto.*;
import com.ok.order.order.service.publisher.OrderEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;


import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderEventPublisher orderEventPublisher;
    @Mock CartService cartService;

    // 한국 시간 2026-10-25 09:00으로 고정
    Clock clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 9, 0);

    OrderService sut;

    @BeforeEach
    void setUp() {
        sut = new OrderService(orderRepository, orderEventPublisher, cartService, clock);
    }

    // 저장된 것처럼 ID가 있는 주문 (구매자 1, 판매자 100, 사과 1kg 10,000원 × 2개)
    private Order savedOrder(Long orderId) {
        Order order = Order.create(1L, List.of(
                new OrderLine(1L, 11L, 100L, "사과", "1kg", 10_000L, 2)));
        ReflectionTestUtils.setField(order, "id", orderId);
        return order;
    }


    // 배송 완료된 저장 주문 (주문 10, 상품 1, 판매자 100, 상품 금액 20,000원)
    private Order deliveredOrder(LocalDateTime deliveredAt) {
        Order order = savedOrder(10L);
        order.markPaid();
        order.markDelivered(100L, deliveredAt);
        ReflectionTestUtils.setField(order.getAllItems().get(0), "id", 1L);
        return order;
    }

    @Test
    void 주문_생성_이벤트에는_주문_ID_구매자_결제_총액을_담는다() {
        // given
        given(orderRepository.findById(10L)).willReturn(Optional.of(savedOrder(10L)));

        // when
        sut.publishCreated(10L);

        // then
        then(orderEventPublisher).should().publishOrderCreatedEvent(10L, 1L, 20_000L);
    }

    @Test
    void 없는_주문은_생성_이벤트를_발행할_수_없다() {
        // given
        given(orderRepository.findById(10L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sut.publishCreated(10L))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
        then(orderEventPublisher).should(never()).publishOrderCreatedEvent(anyLong(), anyLong(), anyLong());
    }

    @Test
    void 주문_목록에는_대표_상품명과_상품_수와_총액을_담는다() {
        // given
        Page<Order> orders = new PageImpl<>(List.of(savedOrder(10L)));
        given(orderRepository.findByBuyerIdOrderByIdDesc(eq(1L), any(Pageable.class))).willReturn(orders);

        // when
        Page<OrderSummaryResult> result = sut.getOrders(1L, 0, 10);

        // then
        OrderSummaryResult summary = result.getContent().get(0);
        assertSoftly(softly -> {
            softly.assertThat(result.getContent()).hasSize(1);
            softly.assertThat(summary.orderId()).isEqualTo(10L);
            softly.assertThat(summary.status()).isEqualTo("PAYMENT_PENDING");
            softly.assertThat(summary.representativeProductName()).isEqualTo("사과");
            softly.assertThat(summary.itemCount()).isEqualTo(1);
            softly.assertThat(summary.totalAmount()).isEqualTo(20_000L);
        });
    }

    @Test
    void 주문_상세에는_주문_시점_상품_정보와_상품별_상태를_담는다() {
        // given
        given(orderRepository.findByIdAndBuyerId(10L, 1L)).willReturn(Optional.of(savedOrder(10L)));

        // when
        OrderDetailResult result = sut.getOrder(1L, 10L);

        // then
        OrderDetailItemResult item = result.sellerGroups().get(0).items().get(0);
        assertSoftly(softly -> {
            softly.assertThat(result.orderId()).isEqualTo(10L);
            softly.assertThat(result.status()).isEqualTo("PAYMENT_PENDING");
            softly.assertThat(result.sellerGroups()).hasSize(1);
            softly.assertThat(result.sellerGroups().get(0).sellerId()).isEqualTo(100L);
            softly.assertThat(item.productName()).isEqualTo("사과");
            softly.assertThat(item.productOptionName()).isEqualTo("1kg");
            softly.assertThat(item.unitPrice()).isEqualTo(10_000L);
            softly.assertThat(item.quantity()).isEqualTo(2);
            softly.assertThat(item.itemAmount()).isEqualTo(20_000L);
            softly.assertThat(item.status()).isEqualTo("PAYMENT_PENDING");
            softly.assertThat(result.totalAmount()).isEqualTo(20_000L);
        });
    }

    @Test
    void 다른_회원의_주문은_없는_주문으로_응답한다() {
        // given: 주문 10은 구매자 1의 것이라, 구매자 2로 찾으면 결과가 없다
        given(orderRepository.findByIdAndBuyerId(10L, 2L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sut.getOrder(2L, 10L))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
    }

    // ---------- 결제 결과 반영 ----------

    @Test
    void 결제_대기_주문에_결제_완료가_오면_결제_완료되고_장바구니에서_빠진다() {
        // given
        Order order = savedOrder(10L);
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        // when
        sut.completePayment(10L);

        // then
        assertSoftly(softly -> {
            softly.assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            softly.assertThat(order.getAllItems().get(0).getStatus()).isEqualTo(OrderItemStatus.PREPARING);
        });
        then(cartService).should().removeOrderItems(1L, List.of(11L));
    }

    @Test
    void 이미_결제_완료된_주문에_결제_완료가_다시_오면_아무것도_바꾸지_않는다() {
        // given
        Order order = savedOrder(10L);
        order.markPaid();
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        // when
        sut.completePayment(10L);

        // then
        then(cartService).should(never()).removeOrderItems(anyLong(), anyList());
    }

    @Test
    void 결제_실패한_주문에_결제_완료가_와도_상태를_바꾸지_않는다() {
        // given
        Order order = savedOrder(10L);
        order.markPaymentFailed();
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        // when
        sut.completePayment(10L);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        then(cartService).should(never()).removeOrderItems(anyLong(), anyList());
    }

    @Test
    void 결제_대기_주문에_결제_실패가_오면_결제_실패가_되고_재고를_풀어야_한다() {
        // given
        Order order = savedOrder(10L);
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        // when
        boolean shouldReleaseStock = sut.failPayment(10L);

        // then
        assertSoftly(softly -> {
            softly.assertThat(shouldReleaseStock).isTrue();
            softly.assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            softly.assertThat(order.getAllItems().get(0).getStatus()).isEqualTo(OrderItemStatus.PAYMENT_FAILED);
        });
        then(cartService).should(never()).removeOrderItems(anyLong(), anyList());
    }

    @Test
    void 이미_결제_실패한_주문에_실패가_다시_오면_상태는_그대로이고_재고_해제만_다시_시도한다() {
        // given
        Order order = savedOrder(10L);
        order.markPaymentFailed();
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        // when
        boolean shouldReleaseStock = sut.failPayment(10L);

        // then
        assertThat(shouldReleaseStock).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    @Test
    void 결제_완료된_주문에_결제_실패가_오면_무시하고_재고를_풀지_않는다() {
        // given
        Order order = savedOrder(10L);
        order.markPaid();
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        // when
        boolean shouldReleaseStock = sut.failPayment(10L);

        // then
        assertThat(shouldReleaseStock).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    // ---------- 결제 이벤트의 주문이 없을 때 ----------

    @Test
    void 결제_이벤트의_주문이_없으면_예외_없이_넘어가고_재고를_풀지_않는다() {
        // given
        given(orderRepository.findById(10L)).willReturn(Optional.empty());

        // when & then (예외를 던지면 이벤트가 계속 재처리됨)
        assertThatCode(() -> sut.completePayment(10L)).doesNotThrowAnyException();
        assertThat(sut.failPayment(10L)).isFalse();
        then(cartService).should(never()).removeOrderItems(anyLong(), anyList());
    }

    // ---------- 배송 상태 반영 ----------

    @Test
    void 배송이_시작되면_해당_판매자의_상품이_배송_중이_된다() {
        // given
        Order order = savedOrder(10L);
        order.markPaid();
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        // when
        sut.startShipping(10L, 100L);

        // then
        assertThat(order.getAllItems().get(0).getStatus()).isEqualTo(OrderItemStatus.SHIPPING);
    }

    @Test
    void 배송이_완료되면_이벤트의_배송_완료_시각을_저장한다() {
        // given
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 10, 20, 15, 0);
        Order order = savedOrder(10L);
        order.markPaid();
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        // when
        sut.completeDelivery(10L, 100L, deliveredAt);

        // then
        assertSoftly(softly -> {
            softly.assertThat(order.getAllItems().get(0).getStatus()).isEqualTo(OrderItemStatus.DELIVERED);
            softly.assertThat(order.getAllItems().get(0).getDeliveredAt()).isEqualTo(deliveredAt);
        });
    }

    @Test
    void 배송_이벤트의_주문이_없으면_예외_없이_넘어간다() {
        // given
        given(orderRepository.findById(10L)).willReturn(Optional.empty());

        // when & then
        assertThatCode(() -> sut.startShipping(10L, 100L)).doesNotThrowAnyException();
        assertThatCode(() -> sut.completeDelivery(10L, 100L, LocalDateTime.of(2026, 10, 20, 15, 0)))
                .doesNotThrowAnyException();
    }

    // ---------- 리뷰 작성 가능 구매 건 ----------

    @Test
    void 리뷰_작성_가능_구매_건은_구매확정_상태만_조회한다() {
        // given
        given(orderRepository.findReviewCandidates(eq(1L), eq(10L), any())).willReturn(List.of());

        // when
        sut.getReviewCandidates(1L, 10L);

        // then
        then(orderRepository).should().findReviewCandidates(1L, 10L, OrderItemStatus.PURCHASE_CONFIRMED);
    }

    @Test
    void 리뷰_작성_가능_구매_건에는_주문과_주문_상품_정보를_담는다() {
        // given: savedOrder = 주문 10, 상품 1, 옵션 11
        Order order = savedOrder(10L);
        OrderItem item = order.getAllItems().get(0);
        ReflectionTestUtils.setField(item, "id", 1L);
        given(orderRepository.findReviewCandidates(eq(1L), eq(1L), any())).willReturn(List.of(item));

        // when
        List<ReviewCandidateResult> results = sut.getReviewCandidates(1L, 1L);

        // then
        assertSoftly(softly -> {
            softly.assertThat(results).hasSize(1);
            softly.assertThat(results.get(0).orderId()).isEqualTo(10L);
            softly.assertThat(results.get(0).orderItemId()).isEqualTo(1L);
            softly.assertThat(results.get(0).productId()).isEqualTo(1L);
            softly.assertThat(results.get(0).productOptionId()).isEqualTo(11L);
        });
    }

    @Test
    void 리뷰_작성_가능_구매_건이_없으면_빈_목록이다() {
        // given
        given(orderRepository.findReviewCandidates(eq(1L), eq(10L), any())).willReturn(List.of());

        // when & then
        assertThat(sut.getReviewCandidates(1L, 10L)).isEmpty();
    }


    // ---------- 구매확정 ----------

    @Test
    void 구매확정하면_상태가_바뀌고_정산용_이벤트를_발행한다() {
        // given
        Order order = deliveredOrder(NOW.minusDays(1));
        given(orderRepository.findByIdAndBuyerId(10L, 1L)).willReturn(Optional.of(order));

        // when
        sut.confirmPurchase(1L, 10L, 1L);

        // then
        assertSoftly(softly -> {
            softly.assertThat(order.getAllItems().get(0).getStatus()).isEqualTo(OrderItemStatus.PURCHASE_CONFIRMED);
            softly.assertThat(order.getAllItems().get(0).getConfirmedAt()).isEqualTo(NOW);
        });
        then(orderEventPublisher).should().publishOrderProductConfirmedEvent(10L, 1L, 100L, 20_000L, 0L);
    }

    @Test
    void 다른_회원의_주문은_구매확정할_수_없다() {
        // given
        given(orderRepository.findByIdAndBuyerId(10L, 2L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sut.confirmPurchase(2L, 10L, 1L))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    void 주문에_없는_상품은_구매확정할_수_없다() {
        // given
        given(orderRepository.findByIdAndBuyerId(10L, 1L)).willReturn(Optional.of(deliveredOrder(NOW.minusDays(1))));

        // when & then
        assertThatThrownBy(() -> sut.confirmPurchase(1L, 10L, 999L))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
    }

    @Test
    void 배송_완료_전에는_구매확정할_수_없고_이벤트도_없다() {
        // given: 배송 준비
        Order order = savedOrder(10L);
        order.markPaid();
        ReflectionTestUtils.setField(order.getAllItems().get(0), "id", 1L);
        given(orderRepository.findByIdAndBuyerId(10L, 1L)).willReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() -> sut.confirmPurchase(1L, 10L, 1L))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.PURCHASE_CONFIRM_NOT_ALLOWED));
        then(orderEventPublisher).should(never())
                .publishOrderProductConfirmedEvent(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void 이미_구매확정된_상품은_다시_확정할_수_없다() {
        // given
        Order order = deliveredOrder(NOW.minusDays(1));
        order.getAllItems().get(0).confirmPurchase(NOW);
        given(orderRepository.findByIdAndBuyerId(10L, 1L)).willReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() -> sut.confirmPurchase(1L, 10L, 1L))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.ALREADY_PURCHASE_CONFIRMED));
    }

    // ---------- 자동 구매확정 ----------

    @Test
    void 자동_구매확정_대상은_지금부터_3일_전을_기준으로_찾는다() {
        // given
        given(orderRepository.findOrderIdsToAutoConfirm(OrderItemStatus.DELIVERED, NOW.minusDays(3)))
                .willReturn(List.of(10L));

        // when & then
        assertThat(sut.findOrderIdsToAutoConfirm()).containsExactly(10L);
    }

    @Test
    void 배송_완료_3일이_지난_상품은_자동_구매확정되고_이벤트를_발행한다() {
        // given
        given(orderRepository.findById(10L)).willReturn(Optional.of(deliveredOrder(NOW.minusDays(4))));

        // when
        int confirmed = sut.autoConfirmPurchases(10L);

        // then
        assertThat(confirmed).isEqualTo(1);
        then(orderEventPublisher).should().publishOrderProductConfirmedEvent(10L, 1L, 100L, 20_000L, 0L);
    }

    @Test
    void 배송_완료_3일이_안_지난_상품은_자동_구매확정되지_않는다() {
        // given
        given(orderRepository.findById(10L)).willReturn(Optional.of(deliveredOrder(NOW.minusDays(2))));

        // when
        int confirmed = sut.autoConfirmPurchases(10L);

        // then
        assertThat(confirmed).isZero();
        then(orderEventPublisher).should(never())
                .publishOrderProductConfirmedEvent(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void 자동_구매확정_주문이_없으면_예외_없이_0을_돌려준다() {
        // given
        given(orderRepository.findById(10L)).willReturn(Optional.empty());

        // when & then
        assertThat(sut.autoConfirmPurchases(10L)).isZero();
    }


    // ---------- 결제 완료 이벤트 ----------

    @Test
    void 결제가_완료되면_배송이_받을_결제_완료_이벤트를_발행한다() {
        // given: 배송지가 등록된 결제 대기 주문 (판매자 100, 사과 1kg 10,000원 × 2)
        Order order = savedOrder(10L);
        order.registerShippingAddress("홍길동", "010-1234-5678", "06236", "서울시 강남구 테헤란로 1", "101호");
        ReflectionTestUtils.setField(order.getAllItems().get(0), "id", 1L);
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        // when
        sut.completePayment(10L);

        // then
        ArgumentCaptor<PaidOrderMessage> captor = ArgumentCaptor.forClass(PaidOrderMessage.class);
        then(orderEventPublisher).should().publishOrderPaidEvent(captor.capture());
        PaidOrderMessage message = captor.getValue();
        assertSoftly(softly -> {
            softly.assertThat(message.orderId()).isEqualTo(10L);
            softly.assertThat(message.buyerId()).isEqualTo(1L);
            softly.assertThat(message.paidAt()).isEqualTo(NOW);
            softly.assertThat(message.addressDetail()).isEqualTo("101호");
            softly.assertThat(message.sellerGroups()).hasSize(1);
            softly.assertThat(message.sellerGroups().get(0).sellerId()).isEqualTo(100L);
            softly.assertThat(message.sellerGroups().get(0).regionSurcharge()).isZero();
            softly.assertThat(message.sellerGroups().get(0).items().get(0).orderItemId()).isEqualTo(1L);
            softly.assertThat(message.sellerGroups().get(0).items().get(0).productName()).isEqualTo("사과");
            softly.assertThat(message.sellerGroups().get(0).items().get(0).optionName()).isEqualTo("1kg");
            softly.assertThat(message.sellerGroups().get(0).items().get(0).quantity()).isEqualTo(2);
            softly.assertThat(message.sellerGroups().get(0).items().get(0).unitPrice()).isEqualTo(10_000L);
        });
    }

    @Test
    void 이미_결제_완료된_주문에는_결제_완료_이벤트를_다시_발행하지_않는다() {
        // given
        Order order = savedOrder(10L);
        order.markPaid();
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        // when
        sut.completePayment(10L);

        // then
        then(orderEventPublisher).should(never()).publishOrderPaidEvent(any());
    }
}
