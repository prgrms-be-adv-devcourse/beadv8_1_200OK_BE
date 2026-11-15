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
 * 호출한 서비스의 트랜잭션 안에서 메시지 본문을 JSON으로 만들어 outbox 줄({@link DeliveryOutbox})로 저장한다. 그래서 출고·배송 완료가
 * 커밋되면 반드시 보내고, 롤백되면 보내지 않는다. 실제 Kafka 발송은 {@code DeliveryOutboxRelayScheduler}가 커밋 뒤에 따로 한다.
 * 출고는 택배사 추적 요청용 배송 안 이벤트({@link CarrierTrackingRequestedEvent})도 같은 트랜잭션에서 낸다. 받는 쪽이 커밋 뒤에 처리한다.
 */
@Component
@RequiredArgsConstructor
public class OutboxDeliveryEventPublisher implements DeliveryEventPublisher {

    private final DeliveryOutboxRepository deliveryOutboxRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    // 본문을 저장할 때 JSON으로 굳힌다. 앱의 JSON 설정(날짜는 ISO-8601 문자열)을 그대로 쓰려고 부트가 만든 것을 받는다
    private final JsonMapper jsonMapper;

    @Override
    public void publishShipmentDispatched(Long shipmentId, Long orderId, Long sellerId,
                                          String carrier, String trackingNumber, LocalDateTime shippedAt) {
        // 메시지를 먼저 만들어 값 검사를 통과한 뒤에만 outbox 줄을 남긴다
        ShipmentDispatchedMessage message = new ShipmentDispatchedMessage(
                shipmentId, orderId, sellerId, carrier, trackingNumber, shippedAt);
        record(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, orderId, message);
        // TODO(#603): 주문이 Kafka(delivery.shipment-dispatched)로 받게 되면 스프링 이벤트 발행을 지운다(이중 경로, #644 결정 ④)
        applicationEventPublisher.publishEvent(
                new ShipmentDispatchedEvent(shipmentId, orderId, sellerId, carrier, trackingNumber, shippedAt));
        applicationEventPublisher.publishEvent(new CarrierTrackingRequestedEvent(shipmentId, carrier, trackingNumber));
    }

    @Override
    public void publishShipmentDelivered(Long shipmentId, Long orderId, Long sellerId, LocalDateTime deliveredAt) {
        ShipmentDeliveredMessage message = new ShipmentDeliveredMessage(shipmentId, orderId, sellerId, deliveredAt);
        record(DeliveryOutboxEventType.SHIPMENT_DELIVERED, orderId, message);
        // TODO(#603): 주문이 Kafka(delivery.shipment-delivered)로 받게 되면 스프링 이벤트 발행을 지운다(이중 경로, #644 결정 ④)
        applicationEventPublisher.publishEvent(new ShipmentDeliveredEvent(shipmentId, orderId, sellerId, deliveredAt));
    }

    // 키를 orderId로 두면 같은 주문의 배송 시작과 완료가 같은 파티션으로 가서 순서가 지켜진다 (#644 메시지 약속)
    private void record(DeliveryOutboxEventType eventType, Long orderId, Object message) {
        deliveryOutboxRepository.save(
                DeliveryOutbox.pending(eventType, String.valueOf(orderId), jsonMapper.writeValueAsString(message)));
    }

}
