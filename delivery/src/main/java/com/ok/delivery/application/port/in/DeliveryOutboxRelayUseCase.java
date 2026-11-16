package com.ok.delivery.application.port.in;

import java.time.Duration;

/**
 * 배송 outbox에 쌓인 메시지를 다른 서비스로 보낸다 (#644).
 */
public interface DeliveryOutboxRelayUseCase {

    /**
     * 보내지 않은 줄을 쌓인 순서대로 최대 {@code batchSize}개 보낸다. 한 줄이라도 실패하면 그 실행은 거기서 멈춘다.
     *
     * @param minAge 남은 지 이 시간이 지난 줄만 보낸다. 커밋 직후 바로 보내는 중인 줄을 함께 보내지 않으려는 것이다
     * @return 이번 실행에서 보낸 줄 수
     */
    int relayPending(int batchSize, Duration minAge);

    /**
     * 줄 하나를 바로 보낸다. 이미 보낸 줄이면 아무것도 하지 않는다.
     * 실패하면 예외를 올리지 않고 실패를 기록해 둔다. 줄은 PENDING으로 남아 {@link #relayPending}이 다시 보낸다.
     */
    void send(Long outboxId);

}
