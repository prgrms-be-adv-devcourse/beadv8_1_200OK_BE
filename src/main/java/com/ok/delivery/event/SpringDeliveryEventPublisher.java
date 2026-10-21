package com.ok.delivery.event;

import com.ok.common.event.DomainEvent;
import com.ok.common.event.ShipmentDeliveredEvent;
import com.ok.common.event.ShipmentDispatchedEvent;
import com.ok.delivery.service.DeliveryEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 배송의 이벤트를 스프링 이벤트로 발행한다.
 *
 * <p>서비스가 넘긴 값으로 모듈 간 이벤트({@link DomainEvent})를 만들어 발행한다.
 * 발행은 호출한 서비스의 트랜잭션 안에서 일어나고, 구독하는 모듈은 커밋 후에 처리한다({@code @ApplicationModuleListener}).
 */
@Component
@RequiredArgsConstructor
public class SpringDeliveryEventPublisher implements DeliveryEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publishShipmentDispatched(Long shipmentId, Long orderId, Long sellerId,
                                          String carrier, String trackingNumber, LocalDateTime shippedAt) {
        publish(new ShipmentDispatchedEvent(shipmentId, orderId, sellerId, carrier, trackingNumber, shippedAt));
    }

    @Override
    public void publishShipmentDelivered(Long shipmentId, Long orderId, Long sellerId, LocalDateTime deliveredAt) {
        publish(new ShipmentDeliveredEvent(shipmentId, orderId, sellerId, deliveredAt));
    }

    // 모듈 간 계약으로 정한 이벤트만 나가도록 DomainEvent만 받는다
    private void publish(DomainEvent event) {
        applicationEventPublisher.publishEvent(event);
    }

}
