package com.ok.order.order.service;

import com.ok.common.exception.RestApiException;
import com.ok.order.cart.service.CartService;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderItemStatus;
import com.ok.order.order.domain.OrderLine;
import com.ok.order.order.domain.OrderStatus;
import com.ok.order.order.exception.OrderErrorCode;
import com.ok.order.order.repository.OrderRepository;
import com.ok.order.order.service.dto.OrderDetailItemResult;
import com.ok.order.order.service.dto.OrderDetailResult;
import com.ok.order.order.service.dto.OrderSummaryResult;
import com.ok.order.order.service.publisher.OrderEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    @InjectMocks OrderService sut;

    // 저장된 것처럼 ID가 있는 주문 (구매자 1, 판매자 100, 사과 1kg 10,000원 × 2개)
    private Order savedOrder(Long orderId) {
        Order order = Order.create(1L, List.of(
                new OrderLine(1L, 11L, 100L, "사과", "1kg", 10_000L, 2)));
        ReflectionTestUtils.setField(order, "id", orderId);
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
}