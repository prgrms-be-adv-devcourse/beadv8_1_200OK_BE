package com.ok.delivery.application.port.out;

import com.ok.delivery.domain.DeliveryOutboxEventType;

/**
 * 배송 outbox에 쌓인 메시지를 메시지 브로커로 보내는 인터페이스 (#644).
 * {@link DeliveryEventPublisher}는 배송 생성·출고·배송 완료 트랜잭션 안에서 "보낼 것"을 남기고, 이쪽은 커밋된 뒤 발송 작업이 실제로 보낸다.
 */
public interface DeliveryMessageSender {

    /**
     * outbox에 저장한 메시지를 보낸다. 브로커가 저장을 확인할 때까지 기다린다.
     *
     * @param payload outbox에 저장한 메시지 본문(JSON)
     * @throws IllegalStateException 브로커가 거절했거나 정해진 시간 안에 확인이 오지 않으면. 메시지는 나갔을 수도 있다
     */
    void send(DeliveryOutboxEventType eventType, String messageKey, String payload);

}
