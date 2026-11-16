package com.ok.delivery.application.dto;

import com.ok.delivery.domain.DeliveryOutboxEventType;

/**
 * 보낼 차례인 outbox 줄 하나. 발송은 트랜잭션 밖에서 하므로 엔티티 대신 보낼 때 필요한 값만 넘긴다.
 *
 * @param payload      저장해 둔 메시지 본문(JSON)
 * @param attemptCount 지금까지 실패한 발송 횟수
 */
public record DeliveryOutboxMessage(Long outboxId, DeliveryOutboxEventType eventType, String messageKey, String payload,
                                    int attemptCount) {
}
