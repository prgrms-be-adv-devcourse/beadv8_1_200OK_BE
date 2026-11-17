package com.ok.order.order.application.port.in;

/**
 * 주문이 Kafka로 보낼 메시지를 outbox에 남긴다 (#666).
 * 주문의 발행 포트 구현(adapter.out)이 부른다. 메시지마다 토픽 상수와 본문 DTO만 더하고, 보내기·다시 보내기는 이 기반이 맡는다.
 */
public interface OrderOutboxRecordUseCase {

    /**
     * 부른 쪽의 트랜잭션 안에서 보낼 메시지 한 줄을 저장하고, 커밋되면 바로 보낸다. 보내지 못하면 재발송 스케줄러가 다시 보낸다.
     * 주문 변경과 함께 커밋되거나 함께 롤백되어야 하므로 트랜잭션 밖에서 부르면 예외를 던진다.
     *
     * @param messageKey Kafka 메시지 키(업무 식별자). 같은 키의 메시지는 같은 파티션으로 가서 순서가 지켜진다
     * @param payload    보낼 메시지 본문(JSON). 기록한 시점의 값이 그대로 나간다
     * @throws org.springframework.transaction.IllegalTransactionStateException 트랜잭션 밖에서 부르면
     */
    void record(String topic, String messageKey, String payload);
}
