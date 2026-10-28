package com.ok.order.order.event;

import com.ok.common.event.OrderCreatedEvent;
import com.ok.common.event.OrderPaidEvent;
import com.ok.common.event.OrderProductConfirmedEvent;
import com.ok.common.event.payload.PaidOrderItem;
import com.ok.common.event.payload.PaidSellerOrder;
import com.ok.common.event.payload.ShippingAddress;
import com.ok.order.order.service.dto.PaidItemMessage;
import com.ok.order.order.service.dto.PaidOrderMessage;
import com.ok.order.order.service.dto.PaidSellerGroupMessage;
import com.ok.order.order.service.publisher.OrderEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SpringOrderEventPublisher implements OrderEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publishOrderCreatedEvent(Long orderId, Long buyerId, Long totalAmount) {
        applicationEventPublisher.publishEvent(new OrderCreatedEvent(orderId, buyerId, totalAmount));
    }

    @Override
    public void publishOrderProductConfirmedEvent(Long orderId, Long orderItemId, Long sellerId, Long totalItemPrice, Long sellerShippingPrice) {
        applicationEventPublisher.publishEvent(new OrderProductConfirmedEvent(orderId, orderItemId, sellerId, totalItemPrice, sellerShippingPrice));
    }

    @Override
    public void publishOrderPaidEvent(PaidOrderMessage message) {
        ShippingAddress shippingAddress = new ShippingAddress(
                message.recipientName(),
                message.recipientPhone(),
                message.zipCode(),
                message.address(),
                message.addressDetail(),
                null   // TODO 배송 요청사항: 주문에 아직 없음
        );

        List<PaidSellerOrder> sellerOrders = new ArrayList<>();
        for (PaidSellerGroupMessage group : message.sellerGroups()) {
            List<PaidOrderItem> items = new ArrayList<>();
            for (PaidItemMessage item : group.items()) {
                items.add(new PaidOrderItem(
                        item.orderItemId(), item.productName(), item.optionName(), item.quantity(), item.unitPrice()));
            }
            sellerOrders.add(new PaidSellerOrder(
                    group.sellerId(), group.baseShippingFee(), group.freeShippingThreshold(), group.regionSurcharge(), items));
        }

        applicationEventPublisher.publishEvent(new OrderPaidEvent(
                message.orderId(), message.buyerId(), message.paidAt(), shippingAddress, sellerOrders));
    }
}
