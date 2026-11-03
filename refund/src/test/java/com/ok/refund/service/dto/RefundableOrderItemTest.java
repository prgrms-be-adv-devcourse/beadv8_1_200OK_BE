package com.ok.refund.service.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefundableOrderItemTest {

    @Test
    void 출고_전이면_취소할_수_있다() {
        // given
        RefundableOrderItem sut = orderItemWithStatus("PREPARING");

        // when & then
        assertThat(sut.isCancellable()).isTrue();
    }

    @Test
    void 출고_전이_아니면_취소할_수_없다() {
        // given
        RefundableOrderItem sut = orderItemWithStatus("SHIPPING");

        // when & then
        assertThat(sut.isCancellable()).isFalse();
    }

    private static RefundableOrderItem orderItemWithStatus(String status) {
        return new RefundableOrderItem(10L, 101L, 1L, 5L, 55L, "양말", "흰색", status, 10_000L, 3);
    }
}
