package com.ok.common.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPaidEventTest {

    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 10, 19, 10, 0);
    private static final ShippingAddress ADDRESS =
            new ShippingAddress("홍길동", "010-1234-5678", "06236", "서울 강남구 테헤란로 1", "101호", null);

    @Test
    void 판매자_묶음_목록은_만든_뒤_원본을_바꿔도_바뀌지_않는다() {
        // given
        List<PaidSellerOrder> sellerOrders = new ArrayList<>(List.of(sellerOrder(10L)));

        // when
        OrderPaidEvent sut = new OrderPaidEvent(1L, PAID_AT, ADDRESS, sellerOrders);
        sellerOrders.add(sellerOrder(20L));

        // then
        assertThat(sut.sellerOrders()).extracting(PaidSellerOrder::sellerId).containsExactly(10L);
    }

    @ParameterizedTest
    @MethodSource("비어_있는_목록")
    void 판매자_묶음이_없으면_예외가_발생한다(List<PaidSellerOrder> sellerOrders) {
        // when & then
        assertThatThrownBy(() -> new OrderPaidEvent(1L, PAID_AT, ADDRESS, sellerOrders))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sellerOrders는 1개 이상이어야 합니다.");
    }

    static Stream<List<?>> 비어_있는_목록() {
        return Stream.of(null, List.of());
    }

    @Test
    void 같은_판매자가_두_번_있으면_예외가_발생한다() {
        // given
        List<PaidSellerOrder> sellerOrders = List.of(sellerOrder(10L), sellerOrder(10L));

        // when & then
        assertThatThrownBy(() -> new OrderPaidEvent(1L, PAID_AT, ADDRESS, sellerOrders))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sellerId가 중복되었습니다: 10");
    }

    @Test
    void 필수값이_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> new OrderPaidEvent(null, PAID_AT, ADDRESS, List.of(sellerOrder(10L))))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("orderId는 필수입니다.");
    }

    private static PaidSellerOrder sellerOrder(Long sellerId) {
        return new PaidSellerOrder(sellerId, 3_000L, 50_000L, 0L, List.of(new PaidOrderItem(100L, "사과", "1kg", 2, 10_000L)));
    }
}
