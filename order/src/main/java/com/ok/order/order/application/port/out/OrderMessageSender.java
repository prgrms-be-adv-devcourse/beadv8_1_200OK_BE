package com.ok.order.order.application.port.out;

/**
 * 메시지 한 건을 메시지 브로커로 보낸다. 구현은 {@code adapter.out.kafka}에 있다.
 * 서비스는 브로커 종류를 모르고 토픽·키·본문만 넘긴다.
 */
public interface OrderMessageSender {

    /**
     * 브로커가 저장을 확인할 때까지 기다린다. 확인하지 못하면(거절, 시간 초과) 예외를 던진다.
     * 확인 없이 돌아오면 브로커가 거절해도 outbox 줄이 보냄으로 바뀌어 다시 보내지 않게 된다.
     *
     * @param payload 메시지 본문(JSON). 받는 쪽은 이 JSON을 그대로 읽는다
     */
    void send(String topic, String messageKey, String payload);
}
