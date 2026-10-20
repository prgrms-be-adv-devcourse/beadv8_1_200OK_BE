package com.ok.delivery.service;

import java.time.LocalDateTime;

/**
 * 배송의 이벤트 발행 인터페이스. 서비스는 이 인터페이스로만 이벤트를 발행한다.
 *
 * <p>발행 방식(스프링 이벤트, 메시지 브로커 등)은 구현체({@code event} 패키지)가 맡고, 이벤트 클래스는 구현체 안에서만 만들도록 값을 받는다.
 */
public interface DeliveryEventPublisher {

    /**
     * 송장 등록으로 배송이 시작되었음을 알린다. 주문은 이 이벤트로 판매자 묶음을 배송 중으로 바꾼다.
     *
     * @param carrier   택배사 코드
     * @param shippedAt 송장 등록(배송 시작) 시각
     */
    void publishShipmentDispatched(Long shipmentId, Long orderId, Long sellerId,
                           String carrier, String trackingNumber, LocalDateTime shippedAt);

}
