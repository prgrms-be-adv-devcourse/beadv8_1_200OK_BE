package com.ok.delivery.adapter.in.event.dto;

import com.ok.delivery.adapter.in.event.dto.OrderPaidMessage.Item;
import com.ok.delivery.adapter.in.event.dto.OrderPaidMessage.SellerOrder;
import com.ok.delivery.adapter.in.event.dto.OrderPaidMessage.ShippingAddress;
import com.ok.delivery.application.dto.AddressCommand;
import com.ok.delivery.application.dto.SellerShipmentCommand;
import com.ok.delivery.application.dto.ShipmentCreateCommand;
import com.ok.delivery.application.dto.ShipmentItemCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class OrderPaidMessageTest {

    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 10, 19, 10, 0);
    private static final ShippingAddress ADDRESS = new ShippingAddress("홍길동", "010-1234-5678", "06236", "주소", "상세 주소", "문 앞");

    @Test
    void 값을_필드마다_그대로_shipment_생성_입력으로_바꾼다() {
        // given: 같은 타입 필드가 뒤바뀌지 않았는지 보려고 필드마다 다른 값을 넣는다
        OrderPaidMessage message = new OrderPaidMessage(1L, 2L, PAID_AT, ADDRESS,
                List.of(new SellerOrder(10L, 3_000L, 50_000L, 4_000L,
                        List.of(new Item(100L, "상품", "옵션", 3, 7_000L)))));

        // when
        ShipmentCreateCommand sut = message.toCommand();

        // then
        assertThat(sut).isNotNull();
        AddressCommand address = sut.address();
        SellerShipmentCommand seller = sut.sellers().getFirst();
        ShipmentItemCommand item = seller.items().getFirst();
        assertSoftly(softly -> {
            softly.assertThat(sut.orderId()).isEqualTo(1L);
            softly.assertThat(sut.buyerId()).isEqualTo(2L);
            softly.assertThat(sut.paidAt()).isEqualTo(PAID_AT);
            softly.assertThat(address).isEqualTo(new AddressCommand("홍길동", "010-1234-5678", "06236", "주소", "상세 주소", "문 앞"));
            softly.assertThat(seller.sellerId()).isEqualTo(10L);
            softly.assertThat(seller.baseShippingFee()).isEqualTo(3_000L);
            softly.assertThat(seller.freeShippingThreshold()).isEqualTo(50_000L);
            softly.assertThat(seller.regionSurcharge()).isEqualTo(4_000L);
            softly.assertThat(item).isEqualTo(new ShipmentItemCommand(100L, "상품", "옵션", 3, 7_000L));
        });
    }

    @Test
    void 무료배송_기준이_없는_판매자도_받는다() {
        // given
        OrderPaidMessage message = message(List.of(new SellerOrder(10L, 3_000L, null, 0L, List.of(item(100L)))));

        // when
        ShipmentCreateCommand sut = message.toCommand();

        // then
        assertThat(sut.sellers().getFirst().freeShippingThreshold()).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidMessages")
    void 값이_틀린_메시지는_IllegalArgumentException을_던진다(OrderPaidMessage message, String expectedMessage) {
        // when & then
        assertThatThrownBy(message::toCommand)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedMessage);
    }

    static Stream<Arguments> invalidMessages() {
        List<SellerOrder> sellers = List.of(seller(10L, item(100L)));
        return Stream.of(
                Arguments.of(new OrderPaidMessage(null, 2L, PAID_AT, ADDRESS, sellers), "orderId는 필수입니다. orderId=null"),
                Arguments.of(new OrderPaidMessage(1L, null, PAID_AT, ADDRESS, sellers), "buyerId는 필수입니다. orderId=1"),
                Arguments.of(new OrderPaidMessage(1L, 2L, null, ADDRESS, sellers), "paidAt은 필수입니다. orderId=1"),
                Arguments.of(new OrderPaidMessage(1L, 2L, PAID_AT, null, sellers), "shippingAddress는 필수입니다. orderId=1"),
                Arguments.of(new OrderPaidMessage(1L, 2L, PAID_AT,
                                new ShippingAddress("홍길동", "010-1234-5678", " ", "주소", "상세 주소", null), sellers),
                        "postalCode는 필수입니다. orderId=1"),
                Arguments.of(message(null), "sellerOrders는 1개 이상이어야 합니다. orderId=1"),
                Arguments.of(message(List.of()), "sellerOrders는 1개 이상이어야 합니다. orderId=1"),
                Arguments.of(message(List.of(seller(null, item(100L)))), "sellerId는 필수입니다. orderId=1"),
                Arguments.of(message(List.of(seller(10L, item(100L)), seller(10L, item(101L)))),
                        "sellerId가 중복되었습니다: 10 orderId=1"),
                Arguments.of(message(List.of(new SellerOrder(10L, null, null, 0L, List.of(item(100L))))),
                        "baseShippingFee는 0 이상이어야 합니다. orderId=1"),
                Arguments.of(message(List.of(new SellerOrder(10L, 3_000L, -1L, 0L, List.of(item(100L))))),
                        "freeShippingThreshold는 0 이상이어야 합니다. orderId=1"),
                Arguments.of(message(List.of(new SellerOrder(10L, 3_000L, null, null, List.of(item(100L))))),
                        "regionSurcharge는 0 이상이어야 합니다. orderId=1"),
                Arguments.of(message(List.of(new SellerOrder(10L, 3_000L, null, 0L, List.of()))),
                        "items는 1개 이상이어야 합니다. sellerId=10 orderId=1"),
                Arguments.of(message(List.of(seller(10L, item(null)))), "orderItemId는 필수입니다. orderId=1"),
                Arguments.of(message(List.of(seller(10L, item(100L)), seller(20L, item(100L)))),
                        "orderItemId가 중복되었습니다: 100 orderId=1"),
                Arguments.of(message(List.of(seller(10L, new Item(100L, "", null, 1, 1_000L)))),
                        "productName는 필수입니다. orderId=1"),
                Arguments.of(message(List.of(seller(10L, new Item(100L, "상품", null, 0, 1_000L)))),
                        "quantity는 1 이상이어야 합니다. orderItemId=100 orderId=1"),
                Arguments.of(message(List.of(seller(10L, new Item(100L, "상품", null, 1, null)))),
                        "unitPrice는 0 이상이어야 합니다. orderId=1"));
    }

    private static OrderPaidMessage message(List<SellerOrder> sellers) {
        return new OrderPaidMessage(1L, 2L, PAID_AT, ADDRESS, sellers);
    }

    private static SellerOrder seller(Long sellerId, Item item) {
        return new SellerOrder(sellerId, 3_000L, 50_000L, 0L, List.of(item));
    }

    private static Item item(Long orderItemId) {
        return new Item(orderItemId, "상품", null, 1, 10_000L);
    }
}
