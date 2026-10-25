package com.ok.common.event.payload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaidSellerOrderTest {

    @Test
    void 상품_항목_목록은_만든_뒤_원본을_바꿔도_바뀌지_않는다() {
        // given
        List<PaidOrderItem> items = new ArrayList<>(List.of(item(100L)));

        // when
        PaidSellerOrder sut = new PaidSellerOrder(10L, 3_000L, 50_000L, 0L, items);
        items.add(item(200L));

        // then
        assertThat(sut.items()).extracting(PaidOrderItem::orderItemId).containsExactly(100L);
    }

    @ParameterizedTest
    @MethodSource("비어_있는_상품_항목")
    void 상품_항목이_없으면_예외가_발생한다(List<PaidOrderItem> items) {
        // when & then
        assertThatThrownBy(() -> new PaidSellerOrder(10L, 3_000L, 50_000L, 0L, items))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("items는 1개 이상이어야 합니다.");
    }

    static Stream<List<PaidOrderItem>> 비어_있는_상품_항목() {
        return Stream.of(null, List.of());
    }

    @ParameterizedTest
    @MethodSource("음수_금액")
    void 금액이_음수면_예외가_발생한다(Long baseShippingFee, Long freeShippingThreshold, Long regionSurcharge, String message) {
        // when & then
        assertThatThrownBy(() -> new PaidSellerOrder(10L, baseShippingFee, freeShippingThreshold, regionSurcharge, List.of(item(100L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    static Stream<Arguments> 음수_금액() {
        return Stream.of(
                Arguments.of(-1L, 50_000L, 0L, "baseShippingFee는 0 이상이어야 합니다."),
                Arguments.of(3_000L, -1L, 0L, "freeShippingThreshold는 0 이상이어야 합니다."),
                Arguments.of(3_000L, 50_000L, -1L, "regionSurcharge는 0 이상이어야 합니다.")
        );
    }

    @Test
    void 금액이_0이면_만들_수_있다() {
        // when
        PaidSellerOrder sut = new PaidSellerOrder(10L, 0L, 0L, 0L, List.of(item(100L)));

        // then
        assertThat(sut.baseShippingFee()).isZero();
    }

    private static PaidOrderItem item(Long orderItemId) {
        return new PaidOrderItem(orderItemId, "사과", "1kg", 2, 10_000L);
    }
}
