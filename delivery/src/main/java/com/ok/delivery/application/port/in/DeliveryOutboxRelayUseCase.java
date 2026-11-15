package com.ok.delivery.application.port.in;

/**
 * 배송 outbox에 쌓인 메시지를 다른 서비스로 보낸다 (#644).
 */
public interface DeliveryOutboxRelayUseCase {

    /**
     * 보내지 않은 줄을 쌓인 순서대로 최대 {@code batchSize}개 보낸다. 한 줄이라도 실패하면 그 실행은 거기서 멈춘다.
     *
     * @return 이번 실행에서 보낸 줄 수
     */
    int relayPending(int batchSize);

}
