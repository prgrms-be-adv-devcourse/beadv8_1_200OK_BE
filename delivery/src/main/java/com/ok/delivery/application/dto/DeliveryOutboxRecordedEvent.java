package com.ok.delivery.application.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * 보낼 outbox 줄이 남았음을 나타내는 배송 안 이벤트다. 커밋 뒤 이 줄을 바로 Kafka로 보내는 데 쓴다 (#644).
 * 발행하는 쪽(adapter.out)과 받는 쪽(adapter.in)이 함께 쓸 수 있는 곳이 application.dto라 여기 둔다 ({@code ArchitectureTest}).
 *
 * @param recordedAt 줄을 남긴 시각. 즉시 발송 대기열에서 오래 기다린 작업을 DB를 읽지 않고 거르는 데 쓴다 (#793)
 */
public record DeliveryOutboxRecordedEvent(Long outboxId, Instant recordedAt) {

    public DeliveryOutboxRecordedEvent {
        Objects.requireNonNull(outboxId, "outboxId는 필수입니다.");
        Objects.requireNonNull(recordedAt, "recordedAt은 필수입니다.");
    }
}
