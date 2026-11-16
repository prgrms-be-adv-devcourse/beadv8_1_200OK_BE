package com.ok.delivery.adapter.in.event;

import com.ok.delivery.adapter.in.event.dto.SellerOriginPostalCodeMessage;
import com.ok.delivery.application.port.in.SellerOriginPostalCodeUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * member가 Kafka로 보내는 판매자 출고지 우편번호를 받는 입구다.
 */
@Component
@RequiredArgsConstructor
public class SellerOriginPostalCodeKafkaListener {

    private final SellerOriginPostalCodeUseCase sellerOriginPostalCodeUseCase;

    // 받는 타입은 배송 DTO. 메시지에 보내는 쪽 클래스 이름이 없어서(타입 헤더 없음) 리스너마다 읽을 타입을 정한다
    // 값이 잘못된 메시지는 변환 과정에서 IllegalArgumentException이 나고, 배송 오류 처리기가 다시 시도하지 않고 DLT로 보낸다.
    // DB 오류 같은 일시적 실패는 예외를 그대로 전파해 간격을 늘려 가며 다시 시도한다
    @KafkaListener(
            topics = DeliveryInboundTopics.SELLER_ORIGIN_POSTAL_CODE,
            groupId = DeliveryInboundTopics.SELLER_ORIGIN_SYNC_GROUP,
            containerFactory = DeliveryKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.delivery.adapter.in.event.dto.SellerOriginPostalCodeMessage")
    void onKafka(SellerOriginPostalCodeMessage message) {
        sellerOriginPostalCodeUseCase.apply(message.toCommand());
    }
}
