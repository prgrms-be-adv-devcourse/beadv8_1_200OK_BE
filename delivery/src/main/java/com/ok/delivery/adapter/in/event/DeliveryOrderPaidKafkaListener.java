package com.ok.delivery.adapter.in.event;

import com.ok.delivery.adapter.in.event.dto.OrderPaidMessage;
import com.ok.delivery.application.port.in.ShipmentUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 주문이 Kafka로 보내는 결제 완료 메시지를 받아 판매자별 shipment를 만든다 (#644).
 * 같은 메시지가 다시 와도 서비스가 주문 ID로 한 번만 만든다. 이중 경로(#644 결정 ④) 동안 스프링 이벤트로 같은 주문이 와도 마찬가지다.
 */
@Component
@RequiredArgsConstructor
public class DeliveryOrderPaidKafkaListener {

    private final ShipmentUseCase shipmentUseCase;

    // 메시지에 보내는 쪽 클래스 이름(타입 헤더)이 없어서 리스너마다 읽을 타입을 정한다.
    // 값이 틀리면 IllegalArgumentException이 나서 배송 오류 처리기(DeliveryKafkaConsumerConfig)가 다시 시도 없이 DLT로 보낸다.
    // DB 오류 같은 일시적 실패는 예외를 그대로 전파해 간격을 늘려 다시 시도하게 한다
    @KafkaListener(
            topics = DeliveryInboundTopics.ORDER_PAID,
            groupId = DeliveryInboundTopics.SHIPMENT_CREATE_GROUP,
            containerFactory = DeliveryKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.delivery.adapter.in.event.dto.OrderPaidMessage")
    void onOrderPaid(OrderPaidMessage message) {
        shipmentUseCase.create(message.toCommand());
    }
}
