package com.ok.order.order.service;

import com.ok.common.exception.RestApiException;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderLine;
import com.ok.order.order.exception.OrderErrorCode;
import com.ok.order.order.repository.OrderRepository;
import com.ok.order.order.service.publisher.OrderEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderEventPublisher orderEventPublisher;

    @InjectMocks OrderService sut;

    // 저장된 것처럼 ID가 있는 주문 (구매자 1, 10,000원 × 2개)
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
}