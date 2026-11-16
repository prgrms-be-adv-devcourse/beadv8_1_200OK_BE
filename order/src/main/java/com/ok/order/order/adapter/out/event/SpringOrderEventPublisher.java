package com.ok.order.order.adapter.out.event;

import com.ok.order.order.adapter.out.event.dto.OrderCreatedMessage;
import com.ok.order.order.adapter.out.event.dto.OrderPaidMessage;
import com.ok.order.order.adapter.out.event.dto.OrderPaymentExpireRequestedMessage;
import com.ok.order.order.adapter.out.event.dto.OrderProductConfirmedMessage;
import com.ok.order.order.application.dto.PaidItemMessage;
import com.ok.order.order.application.dto.PaidOrderMessage;
import com.ok.order.order.application.dto.PaidSellerGroupMessage;
import com.ok.order.order.application.port.in.OrderOutboxRecordUseCase;
import com.ok.order.order.application.port.out.OrderEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SpringOrderEventPublisher implements OrderEventPublisher {

    // Kafka로 보낼 메시지를 outbox에 남기고(#666), 메시지 DTO를 JSON으로 바꾼다.
    // 토픽별 전환(#600, #602, #603)이 각자 자기 메서드만 고치도록 의존성을 먼저 둔다
    private final OrderOutboxRecordUseCase outboxRecorder;
    private final JsonMapper jsonMapper;

    // 지갑은 Kafka로만 받는다(#640). 지갑은 주문 ID로 처리 기록을 남겨 다시 보내져도 한 번만 차감한다(#602).
    @Override
    public void publishOrderCreatedEvent(Long orderId, Long buyerId, Long totalAmount, String orderSummary) {
        // 키를 orderId로 둬 지갑의 결제 결과(키 orderId)와 같은 기준으로 한 주문의 메시지가 같은 파티션에 순서대로 쌓인다
        outboxRecorder.record(OrderTopics.ORDER_CREATED, String.valueOf(orderId),
                jsonMapper.writeValueAsString(new OrderCreatedMessage(orderId, buyerId, totalAmount, orderSummary)));
    }

    // 정산은 Kafka로 받는다(#663). 정산이 orderItemId로 중복을 막으므로 다시 보내져도 정산 후보는 하나다(#600).
    // 키를 orderItemId로 둬 같은 주문 상품의 메시지가 같은 파티션에 쌓인다. 정산의 중복 방지 기준과 같다
    @Override
    public void publishOrderProductConfirmedEvent(Long orderId, Long orderItemId, Long sellerId, Long totalItemPrice, Long sellerShippingPrice) {
        outboxRecorder.record(OrderTopics.ORDER_PRODUCT_CONFIRMED, String.valueOf(orderItemId),
                jsonMapper.writeValueAsString(new OrderProductConfirmedMessage(
                        orderId, orderItemId, sellerId, totalItemPrice, sellerShippingPrice)));
    }

    // 배송은 Kafka로 받는다(배송 #644). 배송이 (주문, 판매자)로 중복을 막으므로 다시 보내져도 배송은 판매자마다 하나다(#603).
    // 키를 orderId로 둬 같은 주문의 메시지가 같은 파티션에 순서대로 쌓인다
    @Override
    public void publishOrderPaidEvent(PaidOrderMessage message) {
        OrderPaidMessage.ShippingAddress shippingAddress = new OrderPaidMessage.ShippingAddress(
                message.recipientName(),
                message.recipientPhone(),
                message.zipCode(),
                message.address(),
                message.addressDetail(),
                message.deliveryNote()
        );

        List<OrderPaidMessage.SellerOrder> sellerOrders = new ArrayList<>();
        for (PaidSellerGroupMessage group : message.sellerGroups()) {
            List<OrderPaidMessage.Item> items = new ArrayList<>();
            for (PaidItemMessage item : group.items()) {
                items.add(new OrderPaidMessage.Item(
                        item.orderItemId(), item.productName(), item.optionName(), item.quantity(), item.unitPrice()));
            }
            sellerOrders.add(new OrderPaidMessage.SellerOrder(
                    group.sellerId(), group.baseShippingFee(), group.freeShippingThreshold(), group.regionSurcharge(), items));
        }

        outboxRecorder.record(OrderTopics.ORDER_PAID, String.valueOf(message.orderId()),
                jsonMapper.writeValueAsString(new OrderPaidMessage(
                        message.orderId(), message.buyerId(), message.paidAt(), shippingAddress, sellerOrders)));
    }

    // 지갑이 Kafka로만 받는다(#656). 전환 전에 없던 메시지라 Spring 이벤트로는 보내지 않는다.
    // 키는 orderId다. 순서는 같은 토픽 안에서만 지켜지므로 만료 요청이 주문 생성(order.order-created.v1)보다 먼저 도착할 수 있다.
    // 그래도 지갑이 만료 실패를 먼저 기록해 두어 늦게 온 주문 생성은 차감하지 않는다
    @Override
    public void publishPaymentExpireRequested(Long orderId, Long buyerId, Long totalAmount) {
        outboxRecorder.record(OrderTopics.ORDER_PAYMENT_EXPIRE_REQUESTED, String.valueOf(orderId),
                jsonMapper.writeValueAsString(new OrderPaymentExpireRequestedMessage(orderId, buyerId, totalAmount)));
    }
}
