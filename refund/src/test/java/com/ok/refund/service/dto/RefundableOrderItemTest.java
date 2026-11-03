package com.ok.refund.service.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RefundableOrderItemTest {

    @Test
    void 출고_전이면_취소할_수_있다() {
        // given
        RefundableOrderItem sut = orderItemWithStatus("PREPARING");

        // when & then
        assertThat(sut.isCancellable()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("출고_전이_아닌_상태")
    void 출고_전이_아니면_취소할_수_없다(String status) {
        // given
        RefundableOrderItem sut = orderItemWithStatus(status);

        // when & then
        assertThat(sut.isCancellable()).isFalse();
    }

    // 주문 모듈 상태 이름을 모두 알지 못해, 출고 뒤·끝난 상태와 함께 대소문자가 다른 값·빈 값·null도 막히는지 본다
    static Stream<String> 출고_전이_아닌_상태() {
        return Arrays.stream(new String[]{"SHIPPING", "DELIVERED", "REFUNDED", "preparing", "", null});
    }

    private static RefundableOrderItem orderItemWithStatus(String status) {
        return new RefundableOrderItem(10L, 101L, 1L, 5L, 55L, "양말", "흰색", status, 10_000L, 3);
    }
}
