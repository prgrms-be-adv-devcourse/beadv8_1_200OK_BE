package com.ok.delivery.adapter.in.event.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderProductConfirmedMessageTest {

    @Test
    void 주문_상품_ID를_꺼낸다() {
        // given
        OrderProductConfirmedMessage sut = new OrderProductConfirmedMessage(1L, 101L);

        // when
        Long orderItemId = sut.toOrderItemId();

        // then
        assertThat(orderItemId).isEqualTo(101L);
    }

    @Test
    void 주문_상품_ID가_없으면_IllegalArgumentException을_던진다() {
        // given
        OrderProductConfirmedMessage sut = new OrderProductConfirmedMessage(1L, null);

        // when & then
        assertThatThrownBy(sut::toOrderItemId)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orderItemId는 필수입니다. orderId=1");
    }
}
