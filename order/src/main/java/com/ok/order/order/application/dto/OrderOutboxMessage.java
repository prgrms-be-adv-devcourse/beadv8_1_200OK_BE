package com.ok.order.order.application.dto;

/**
 * 보낼 차례인 outbox 줄 하나다. 보내는 동안 DB 연결을 쥐지 않도록 엔티티 대신 값으로 넘긴다.
 *
 * @param attemptCount 지금까지 실패한 발송 시도 횟수
 */
public record OrderOutboxMessage(
        Long outboxId,
        String topic,
        String messageKey,
        String payload,
        int attemptCount
) {
}
