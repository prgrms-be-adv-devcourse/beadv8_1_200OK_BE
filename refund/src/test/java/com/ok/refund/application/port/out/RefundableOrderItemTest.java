package com.ok.refund.application.port.out;

import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
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

    @ParameterizedTest
    @MethodSource("출고되거나_취소된_상태")
    void 알려진_출고_이후_상태는_더_이상_취소할_수_없다(String status) {
        // given
        RefundableOrderItem sut = orderItemWithStatus(status);

        // when & then
        assertThat(sut.isNoLongerCancellable()).isTrue();
    }

    static Stream<String> 출고되거나_취소된_상태() {
        return Stream.of("SHIPPING", "DELIVERED", "PURCHASE_CONFIRMED", "CANCEL_REQUESTED", "REFUNDED",
                "PAYMENT_PENDING", "PAYMENT_FAILED", "CANCELED");
    }

    // PREPARING은 취소할 수 있는 상태이고, null·모르는 값은 거절 대상이 아니라 오류로 드러내야 해서 모두 false다
    @ParameterizedTest
    @MethodSource("거절_대상이_아닌_상태")
    void 출고_전이거나_모르는_상태는_더_이상_취소할_수_없음이_아니다(String status) {
        // given
        RefundableOrderItem sut = orderItemWithStatus(status);

        // when & then
        assertThat(sut.isNoLongerCancellable()).isFalse();
    }

    static Stream<String> 거절_대상이_아닌_상태() {
        return Arrays.stream(new String[]{"PREPARING", null, "UNKNOWN"});
    }

    private static RefundableOrderItem orderItemWithStatus(String status) {
        BundleShipping bundle = new BundleShipping(0, 0, 3_000L, 50_000L, List.of(new BundleItem(101L, 10_000L, 3)));
        return new RefundableOrderItem(10L, 101L, 1L, 5L, 55L, "감자", "1kg", status, 10_000L, 3, bundle);
    }
}
