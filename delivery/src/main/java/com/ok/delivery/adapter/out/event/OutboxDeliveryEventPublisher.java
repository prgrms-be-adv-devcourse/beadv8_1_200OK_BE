package com.ok.delivery.adapter.out.event;

import com.ok.common.event.ShipmentDeliveredEvent;
import com.ok.common.event.ShipmentDispatchedEvent;
import com.ok.delivery.adapter.out.event.dto.ShipmentDeliveredMessage;
import com.ok.delivery.adapter.out.event.dto.ShipmentDispatchedMessage;
import com.ok.delivery.application.dto.CarrierTrackingRequestedEvent;
import com.ok.delivery.application.port.out.DeliveryEventPublisher;
import com.ok.delivery.application.port.out.DeliveryOutboxRepository;
import com.ok.delivery.domain.DeliveryOutbox;
import com.ok.delivery.domain.DeliveryOutboxEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

/**
 * 배송 이벤트를 outbox에 남겨 다른 서비스로 보낸다 (#644).
 * 호출한 서비스의 트랜잭션 안에서 메시지 본문을 JSON으로 만들어 outbox 줄로 저장하므로, 출고·배송 완료가 커밋되면 반드시 보내고 롤백되면 보내지 않는다.
 * 실제 Kafka 발송은 {@code DeliveryOutboxRelayScheduler}가 커밋 뒤에 따로 한다.
 */
@Component
@RequiredArgsConstructor
public class OutboxDeliveryEventPublisher implements DeliveryEventPublisher {

    private final DeliveryOutboxRepository deliveryOutboxRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    // 앱의 JSON 설정(날짜는 ISO-8601 문자열)을 그대로 쓰려고 부트가 만든 것을 받는다
    private final JsonMapper jsonMapper;

    @Override
    public void publishShipmentDispatched(Long shipmentId, Long orderId, Long sellerId,
                                          String carrier, String trackingNumber, LocalDateTime shippedAt) {
        // 메시지를 먼저 만들어 값 검사를 통과한 뒤에만 outbox 줄을 남긴다
        ShipmentDispatchedMessage message = new ShipmentDispatchedMessage(
                shipmentId, orderId, sellerId, carrier, trackingNumber, shippedAt);
        // 키를 orderId로 두면 같은 주문의 배송 메시지가 같은 파티션으로 가서 순서가 지켜진다 (#644 메시지 약속)
        deliveryOutboxRepository.save(DeliveryOutbox.pending(
                DeliveryOutboxEventType.SHIPMENT_DISPATCHED, String.valueOf(orderId), jsonMapper.writeValueAsString(message)));
        // TODO(#603): 주문이 Kafka로만 받게 되면 스프링 이벤트 발행을 지운다(이중 경로, #644 결정 ④)
        applicationEventPublisher.publishEvent(
                new ShipmentDispatchedEvent(shipmentId, orderId, sellerId, carrier, trackingNumber, shippedAt));
        applicationEventPublisher.publishEvent(new CarrierTrackingRequestedEvent(shipmentId, carrier, trackingNumber));
    }

    @Override
    public void publishShipmentDelivered(Long shipmentId, Long orderId, Long sellerId, LocalDateTime deliveredAt) {
        ShipmentDeliveredMessage message = new ShipmentDeliveredMessage(shipmentId, orderId, sellerId, deliveredAt);
        deliveryOutboxRepository.save(DeliveryOutbox.pending(
                DeliveryOutboxEventType.SHIPMENT_DELIVERED, String.valueOf(orderId), jsonMapper.writeValueAsString(message)));
        // TODO(#603): 주문이 Kafka로만 받게 되면 스프링 이벤트 발행을 지운다(이중 경로, #644 결정 ④)
        applicationEventPublisher.publishEvent(new ShipmentDeliveredEvent(shipmentId, orderId, sellerId, deliveredAt));
    }

}
