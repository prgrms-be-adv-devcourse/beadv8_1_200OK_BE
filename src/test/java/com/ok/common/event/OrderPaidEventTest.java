package com.ok.common.event;

import com.ok.common.event.payload.PaidOrderItem;
import com.ok.common.event.payload.PaidSellerOrder;
import com.ok.common.event.payload.ShippingAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
        List<PaidSellerOrder> sellerOrders = new ArrayList<>(List.of(sellerOrder(10L, 100L)));

        // when
        OrderPaidEvent sut = new OrderPaidEvent(1L, PAID_AT, ADDRESS, sellerOrders);
        sellerOrders.add(sellerOrder(20L, 200L));

        // then
        assertThat(sut.sellerOrders()).extracting(PaidSellerOrder::sellerId).containsExactly(10L);
    }

    @ParameterizedTest
    @MethodSource("필수값이_빠진_이벤트")
    void 필수값이_없으면_예외가_발생한다(Long orderId, LocalDateTime paidAt, ShippingAddress address, String message) {
        // when & then
        assertThatThrownBy(() -> new OrderPaidEvent(orderId, paidAt, address, List.of(sellerOrder(10L, 100L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    static Stream<Arguments> 필수값이_빠진_이벤트() {
        return Stream.of(
                Arguments.of(null, PAID_AT, ADDRESS, "orderId는 필수입니다."),
                Arguments.of(1L, null, ADDRESS, "paidAt은 필수입니다."),
                Arguments.of(1L, PAID_AT, null, "shippingAddress는 필수입니다.")
        );
    }

    @ParameterizedTest
    @MethodSource("비어_있는_판매자_묶음")
    void 판매자_묶음이_없으면_예외가_발생한다(List<PaidSellerOrder> sellerOrders) {
        // when & then
        assertThatThrownBy(() -> new OrderPaidEvent(1L, PAID_AT, ADDRESS, sellerOrders))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sellerOrders는 1개 이상이어야 합니다.");
    }

    static Stream<List<PaidSellerOrder>> 비어_있는_판매자_묶음() {
        return Stream.of(null, List.of());
    }

    @Test
    void 같은_판매자가_두_번_있으면_예외가_발생한다() {
        // given
        List<PaidSellerOrder> sellerOrders = List.of(sellerOrder(10L, 100L), sellerOrder(10L, 200L));

        // when & then
        assertThatThrownBy(() -> new OrderPaidEvent(1L, PAID_AT, ADDRESS, sellerOrders))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sellerId가 중복되었습니다: 10");
    }

    @Test
    void 다른_판매자_묶음에_같은_주문_항목이_있으면_예외가_발생한다() {
        // given
        List<PaidSellerOrder> sellerOrders = List.of(sellerOrder(10L, 100L), sellerOrder(20L, 100L));

        // when & then
        assertThatThrownBy(() -> new OrderPaidEvent(1L, PAID_AT, ADDRESS, sellerOrders))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orderItemId가 중복되었습니다: 100");
    }

    @Test
    void 같은_판매자_묶음_안에_같은_주문_항목이_있으면_예외가_발생한다() {
        // given
        PaidOrderItem item = new PaidOrderItem(100L, "사과", "1kg", 2, 10_000L);
        List<PaidSellerOrder> sellerOrders = List.of(new PaidSellerOrder(10L, 3_000L, 50_000L, 0L, List.of(item, item)));

        // when & then
        assertThatThrownBy(() -> new OrderPaidEvent(1L, PAID_AT, ADDRESS, sellerOrders))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orderItemId가 중복되었습니다: 100");
    }

    private static PaidSellerOrder sellerOrder(Long sellerId, Long orderItemId) {
        return new PaidSellerOrder(sellerId, 3_000L, 50_000L, 0L,
                List.of(new PaidOrderItem(orderItemId, "사과", "1kg", 2, 10_000L)));
    }
}
