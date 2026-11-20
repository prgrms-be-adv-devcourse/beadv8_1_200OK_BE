package com.ok.order.order.adapter.in.event;

import com.ok.order.order.adapter.in.event.dto.ShipmentDeliveredMessage;
import com.ok.order.order.adapter.in.event.dto.ShipmentDispatchedMessage;
import com.ok.order.order.application.port.in.UpdateShippingStatusUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 배송이 Kafka로 보내는 배송 시작·완료를 받아 주문 상품 상태를 바꾼다(#603).
 * 같은 메시지가 여러 번 오거나 배송 완료가 배송 시작보다 먼저 와도 주문 상품 상태를 보고 처리하므로 결과가 같다.
 * 배송 완료는 배송 준비 상태에서도 바로 완료로 바뀌고, 그 뒤에 온 배송 시작은 무시한다.
 * 처리 중 예외는 주문 오류 처리기({@link OrderKafkaConsumerConfig})가 다시 시도한 뒤 실패 기록 표에 남긴다.
 * 배송 상태는 다시 오지 않으므로 메시지를 넘기지 않고 남기는 것이 중요하다.
 */
@Component
@RequiredArgsConstructor
class ShipmentKafkaListener {

    private final UpdateShippingStatusUseCase updateShippingStatus;

    // 메시지에 보내는 쪽 클래스 이름(타입 헤더)이 없어서 읽을 타입을 리스너가 정한다
    @KafkaListener(
            topics = DeliveryTopics.SHIPMENT_DISPATCHED,
            groupId = DeliveryTopics.SHIPPING_STATUS_GROUP,
            containerFactory = OrderKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.order.order.adapter.in.event.dto.ShipmentDispatchedMessage")
    void onDispatched(ShipmentDispatchedMessage message) {
        requireIds(message.orderId(), message.sellerId());
        updateShippingStatus.startShipping(message.orderId(), message.sellerId());
    }

    @KafkaListener(
            topics = DeliveryTopics.SHIPMENT_DELIVERED,
            groupId = DeliveryTopics.SHIPPING_STATUS_GROUP,
            containerFactory = OrderKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.order.order.adapter.in.event.dto.ShipmentDeliveredMessage")
    void onDelivered(ShipmentDeliveredMessage message) {
        requireIds(message.orderId(), message.sellerId());
        // 배송 완료 시각은 자동 구매확정 기간의 기준이라 없으면 처리할 수 없다
        if (message.deliveredAt() == null) {
            throw new IllegalArgumentException("배송 완료 메시지에 배송 완료 시각이 없습니다. orderId=" + message.orderId());
        }
        updateShippingStatus.completeDelivery(message.orderId(), message.sellerId(), message.deliveredAt());
    }

    // 주문·판매자 ID가 없으면 다시 받아도 처리할 수 없다. IllegalArgumentException은 오류 처리기가 다시 시도하지 않고 바로 실패 기록에 남긴다
    private void requireIds(Long orderId, Long sellerId) {
        if (orderId == null || sellerId == null) {
            throw new IllegalArgumentException("배송 메시지에 주문 ID나 판매자 ID가 없습니다. orderId=" + orderId + ", sellerId=" + sellerId);
        }
    }
}
