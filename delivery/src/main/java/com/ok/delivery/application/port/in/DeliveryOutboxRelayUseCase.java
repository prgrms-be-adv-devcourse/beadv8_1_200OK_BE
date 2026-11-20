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

}
