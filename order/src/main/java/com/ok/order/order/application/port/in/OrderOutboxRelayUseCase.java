package com.ok.order.order.application.port.in;

/**
 * outbox에 쌓인 메시지 중 아직 보내지 못한 줄을 다시 보낸다 (#666). 재발송 스케줄러가 쓴다.
 */
public interface OrderOutboxRelayUseCase {

    /**
     * 보낼 차례인 줄을 쌓인 순서대로 최대 {@code batchSize}개 보낸다. 한 줄이라도 실패하면 그 회차는 거기서 멈춘다.
     *
     * @return 이번에 보낸 줄 수
     */
    int relayPending(int batchSize);
}
