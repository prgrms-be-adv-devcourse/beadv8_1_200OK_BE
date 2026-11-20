package com.ok.refund.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 주문 모듈이 보낸 값이 깨졌으면 금액 계산 전에 표준 예외(500)로 막는지 본다(설계 2장)
class BundleShippingTest {

    private static final List<BundleItem> ITEMS = List.of(new BundleItem(101L, 10_000L, 1));

    @ParameterizedTest
    @MethodSource("음수가_있는_묶음_배송비")
    void 묶음_배송비_값이_음수면_막는다(long shippingFee, long remoteAreaFee, long baseShippingFee) {
        // when & then
        assertThatThrownBy(() -> new BundleShipping(shippingFee, remoteAreaFee, baseShippingFee, 50_000L, ITEMS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("묶음 배송비·도서산간 운임·기본 배송비는 0 이상이어야 합니다.");
    }

    static Stream<Arguments> 음수가_있는_묶음_배송비() {
        return Stream.of(
                Arguments.of(-1L, 0L, 3_000L),
                Arguments.of(0L, -1L, 3_000L),
                Arguments.of(0L, 0L, -1L));
    }

    @Test
    void 무료배송_기준이_음수면_막는다() {
        // when & then
        assertThatThrownBy(() -> new BundleShipping(0, 0, 3_000L, -1L, ITEMS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("무료배송 기준 금액은 0 이상이어야 합니다.");
    }

    @ParameterizedTest
    @MethodSource("비어_있는_상품_목록")
    void 상품_목록이_비면_막는다(List<BundleItem> items) {
        // when & then
        assertThatThrownBy(() -> new BundleShipping(0, 0, 3_000L, 50_000L, items))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("묶음 상품 목록은 비어 있을 수 없습니다.");
    }

    static Stream<List<BundleItem>> 비어_있는_상품_목록() {
        return Stream.of(null, List.of());
    }

    @Test
    void 상품_목록에_빈_값이_있으면_막는다() {
        // given
        List<BundleItem> items = new ArrayList<>();
        items.add(null);

        // when & then
        assertThatThrownBy(() -> new BundleShipping(0, 0, 3_000L, 50_000L, items))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("묶음 상품 목록에 빈 값이 있습니다.");
    }

    @Test
    void 같은_상품이_두_번_오면_막는다() {
        // given
        List<BundleItem> items = List.of(new BundleItem(101L, 10_000L, 1), new BundleItem(101L, 10_000L, 2));

        // when & then
        assertThatThrownBy(() -> new BundleShipping(0, 0, 3_000L, 50_000L, items))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("묶음 상품 목록에 같은 주문 상품이 두 번 있습니다. orderItemId=101");
    }

    @Test
    void 묶음_상품의_주문_상품_ID가_없으면_막는다() {
        // when & then
        assertThatThrownBy(() -> new BundleItem(null, 10_000L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("묶음 상품의 주문 상품 ID는 필수입니다.");
    }

    @Test
    void 묶음_상품의_단가가_음수면_막는다() {
        // when & then
        assertThatThrownBy(() -> new BundleItem(101L, -1L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("묶음 상품의 단가는 0 이상이어야 합니다.");
    }

    @Test
    void 묶음_상품의_주문_개수가_1_미만이면_막는다() {
        // when & then
        assertThatThrownBy(() -> new BundleItem(101L, 10_000L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("묶음 상품의 주문 개수는 1 이상이어야 합니다.");
    }
}
