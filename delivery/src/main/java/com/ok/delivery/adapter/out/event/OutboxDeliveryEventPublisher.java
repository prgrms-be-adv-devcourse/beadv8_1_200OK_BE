package com.ok.delivery.adapter.out.event;

import com.ok.common.event.ShipmentDeliveredEvent;
import com.ok.common.event.ShipmentDispatchedEvent;
import com.ok.delivery.adapter.out.event.dto.ShipmentCreatedMessage;
import com.ok.delivery.adapter.out.event.dto.ShipmentDeliveredMessage;
import com.ok.delivery.adapter.out.event.dto.ShipmentDispatchedMessage;
import com.ok.delivery.application.dto.CarrierTrackingRequestedEvent;
import com.ok.delivery.application.dto.DeliveryOutboxRecordedEvent;
import com.ok.delivery.application.port.out.DeliveryEventPublisher;
import com.ok.delivery.application.port.out.DeliveryOutboxRepository;
import com.ok.delivery.domain.DeliveryOutbox;
import com.ok.delivery.domain.DeliveryOutboxEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 배송 이벤트를 outbox에 남겨 다른 서비스로 보낸다 (#644).
 * 호출한 서비스의 트랜잭션 안에서 메시지 본문을 JSON으로 만들어 outbox 줄로 저장하므로, 배송 생성·출고·배송 완료가 커밋되면 반드시 보내고 롤백되면 보내지 않는다.
 * 줄을 남기면서 배송 안 이벤트({@link DeliveryOutboxRecordedEvent})를 내, 커밋 뒤 {@code DeliveryOutboxSendListener}가 그 줄을 바로 Kafka로 보낸다.
 * 거기서 못 보낸 줄은 {@code DeliveryOutboxRelayScheduler}가 보낸다.
 */
@Component
@RequiredArgsConstructor
public class OutboxDeliveryEventPublisher implements DeliveryEventPublisher {

    private final DeliveryOutboxRepository deliveryOutboxRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    // 앱의 JSON 설정(날짜는 ISO-8601 문자열)을 그대로 쓰려고 부트가 만든 것을 받는다
    private final JsonMapper jsonMapper;

    // 예전부터 받던 쪽이 없는 새 메시지라 이중 경로 없이 Kafka로만 보낸다(주문용 스프링 이벤트를 내지 않음)
    @Override
    public void publishShipmentCreated(Long shipmentId, Long orderId, Long sellerId, List<Long> orderItemIds,
                                       LocalDateTime createdAt) {
        ShipmentCreatedMessage message = new ShipmentCreatedMessage(shipmentId, orderId, sellerId, orderItemIds, createdAt);
        record(DeliveryOutboxEventType.SHIPMENT_CREATED, orderId, message);
    }

    @Override
    public void publishShipmentDispatched(Long shipmentId, Long orderId, Long sellerId,
                                          String carrier, String trackingNumber, LocalDateTime shippedAt) {
        // 메시지를 먼저 만들어 값 검사를 통과한 뒤에만 outbox 줄을 남긴다
        ShipmentDispatchedMessage message = new ShipmentDispatchedMessage(
                shipmentId, orderId, sellerId, carrier, trackingNumber, shippedAt);
        // 키를 orderId로 두면 같은 주문의 배송 메시지가 같은 파티션으로 가서 순서가 지켜진다 (#644 메시지 약속)
        record(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, orderId, message);
        // TODO(#603): 주문이 Kafka로만 받게 되면 스프링 이벤트 발행을 지운다(이중 경로, #644 결정 ④)
        applicationEventPublisher.publishEvent(
                new ShipmentDispatchedEvent(shipmentId, orderId, sellerId, carrier, trackingNumber, shippedAt));
        applicationEventPublisher.publishEvent(new CarrierTrackingRequestedEvent(shipmentId, carrier, trackingNumber));
    }

    @Override
    public void publishShipmentDelivered(Long shipmentId, Long orderId, Long sellerId, LocalDateTime deliveredAt) {
        ShipmentDeliveredMessage message = new ShipmentDeliveredMessage(shipmentId, orderId, sellerId, deliveredAt);
        record(DeliveryOutboxEventType.SHIPMENT_DELIVERED, orderId, message);
        // TODO(#603): 주문이 Kafka로만 받게 되면 스프링 이벤트 발행을 지운다(이중 경로, #644 결정 ④)
        applicationEventPublisher.publishEvent(new ShipmentDeliveredEvent(shipmentId, orderId, sellerId, deliveredAt));
    }

    private void record(DeliveryOutboxEventType eventType, Long orderId, Object message) {
        DeliveryOutbox outbox = deliveryOutboxRepository.save(
                DeliveryOutbox.pending(eventType, String.valueOf(orderId), jsonMapper.writeValueAsString(message)));
        applicationEventPublisher.publishEvent(new DeliveryOutboxRecordedEvent(outbox.getId()));
    }

}
