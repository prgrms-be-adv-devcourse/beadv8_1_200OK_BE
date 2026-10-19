package com.ok.delivery.event;

import com.ok.common.event.OrderPaidEvent;
import com.ok.common.event.payload.PaidOrderItem;
import com.ok.common.event.payload.PaidSellerOrder;
import com.ok.common.event.payload.ShippingAddress;
import com.ok.delivery.service.ShipmentService;
import com.ok.delivery.service.dto.AddressCommand;
import com.ok.delivery.service.dto.SellerShipmentCommand;
import com.ok.delivery.service.dto.ShipmentCreateCommand;
import com.ok.delivery.service.dto.ShipmentItemCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 주문의 결제 완료 이벤트를 받아 판매자별 shipment를 만든다.
 */
@Component
@RequiredArgsConstructor
public class OrderPaidEventListener {

    private final ShipmentService shipmentService;

    /**
     * 이벤트를 서비스 입력으로 바꿔 넘긴다. 같은 이벤트가 다시 와도 서비스가 주문 ID로 한 번만 만든다.
     * 배송지 검증 실패나 DB 오류는 예외를 그대로 전파해 처리되지 않은 이벤트로 남긴다.
     */
    @ApplicationModuleListener
    void on(OrderPaidEvent event) {
        shipmentService.create(new ShipmentCreateCommand(
                event.orderId(),
                event.paidAt(),
                toAddress(event.shippingAddress()),
                event.sellerOrders().stream().map(OrderPaidEventListener::toSeller).toList()));
    }

    private static AddressCommand toAddress(ShippingAddress address) {
        return new AddressCommand(
                address.recipientName(),
                address.recipientPhone(),
                address.postalCode(),
                address.address(),
                address.addressDetail(),
                address.deliveryNote());
    }

    private static SellerShipmentCommand toSeller(PaidSellerOrder seller) {
        return new SellerShipmentCommand(
                seller.sellerId(),
                seller.baseShippingFee(),
                seller.freeShippingThreshold(),
                seller.regionSurcharge(),
                seller.items().stream().map(OrderPaidEventListener::toItem).toList());
    }

    private static ShipmentItemCommand toItem(PaidOrderItem item) {
        return new ShipmentItemCommand(
                item.orderItemId(),
                item.productName(),
                item.optionName(),
                item.quantity(),
                item.unitPrice());
    }
}
