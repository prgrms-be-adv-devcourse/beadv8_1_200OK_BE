package com.ok.delivery.adapter.in.event;

import com.ok.delivery.adapter.in.event.dto.OrderProductConfirmedMessage;
import com.ok.delivery.application.port.in.ShipmentUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 주문이 Kafka로 보내는 구매확정 메시지를 받아 shipment 항목을 확정하고, 모든 항목이 확정되면 거래를 완료한다 (#644 결정 ⑥).
 * 구매확정은 주문이 정하고 배송은 받기만 한다. 같은 메시지가 다시 와도 서비스가 결과를 같게 둔다.
 */
@Component
@RequiredArgsConstructor
public class DeliveryPurchaseConfirmedKafkaListener {

    private final ShipmentUseCase shipmentUseCase;

    // 읽을 타입과 오류 처리는 결제 완료 리스너(DeliveryOrderPaidKafkaListener)와 같다
    @KafkaListener(
            topics = DeliveryInboundTopics.ORDER_PRODUCT_CONFIRMED,
            groupId = DeliveryInboundTopics.PURCHASE_CONFIRM_GROUP,
            containerFactory = DeliveryKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.delivery.adapter.in.event.dto.OrderProductConfirmedMessage")
    void onOrderProductConfirmed(OrderProductConfirmedMessage message) {
        shipmentUseCase.confirmPurchase(message.toOrderItemId());
    }
}
