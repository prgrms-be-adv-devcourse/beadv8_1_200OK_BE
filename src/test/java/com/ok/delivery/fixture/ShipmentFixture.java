package com.ok.delivery.fixture;

import com.ok.delivery.domain.Address;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentItem;
import com.ok.delivery.domain.ShipmentStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class ShipmentFixture {

    public static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 11, 12, 0);

    private ShipmentFixture() {
    }

    public static Address address() {
        return Address.of("홍길동", "01012345678", "06236", "서울시 강남구 테헤란로 1", "101동 202호", "문 앞에 놓아주세요");
    }

    public static Address otherAddress() {
        return Address.of("김철수", "0212345678", "63100", "제주특별자치도 제주시 첨단로 1", "2층", null);
    }

    public static ShipmentItem item(long orderItemId) {
        return ShipmentItem.create(orderItemId, "무선 키보드", "블랙", 2, 30_000);
    }

    public static Shipment shipment(ShipmentItem... items) {
        return shipment(1L, 10L, items);
    }

    public static Shipment shipment(long orderId, long sellerId, ShipmentItem... items) {
        return Shipment.builder()
                .orderId(orderId)
                .sellerId(sellerId)
                .address(address())
                .regionGroup("GENERAL")
                .baseShippingFee(3_000)
                .regionSurcharge(0)
                .freeShippingThreshold(50_000)
                .estimatedDeliveryStart(LocalDate.of(2026, 10, 13))
                .estimatedDeliveryEnd(LocalDate.of(2026, 10, 14))
                .items(List.of(items))
                .build();
    }

    // 허용된 전이만 거쳐 원하는 상태의 shipment를 만든다
    public static Shipment shipmentIn(ShipmentStatus status) {
        Shipment shipment = shipment(item(1L), item(2L));
        switch (status) {
            case PREPARING -> {
            }
            case SHIPPING -> shipment.dispatch("CJ_LOGISTICS", "1234567890", NOW);
            case DELIVERED -> {
                shipment.dispatch("CJ_LOGISTICS", "1234567890", NOW);
                shipment.deliver(NOW);
            }
            case COMPLETED -> {
                shipment.dispatch("CJ_LOGISTICS", "1234567890", NOW);
                shipment.deliver(NOW);
                shipment.getItems().forEach(ShipmentItem::confirm);
                shipment.complete(NOW);
            }
            case CANCELLED -> {
                shipment.getItems().forEach(item -> item.cancel(item.getQuantity()));
                shipment.cancel(NOW);
            }
        }
        return shipment;
    }

    // 저장하지 않은 shipment에 id를 넣는다 (변경 요청 생성에 shipment id가 필요)
    public static Shipment withId(Shipment shipment, long id) {
        ReflectionTestUtils.setField(shipment, "id", id);
        return shipment;
    }
}
