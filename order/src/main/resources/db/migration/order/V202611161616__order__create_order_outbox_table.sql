-- 주문이 Kafka로 보낼 메시지 1건당 1줄 (#666)
-- 주문 변경과 같은 트랜잭션에서 PENDING으로 저장하고, 커밋 직후 또는 재발송 스케줄러가 보낸 뒤 SENT로 바꾼다
-- 메시지 종류 대신 토픽 이름을 저장해, 발송기가 메시지 종류를 몰라도 되게 한다. 보낸 줄은 지우지 않는다(무엇을 언제 보냈는지 남김)
CREATE TABLE order_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic VARCHAR(249) NOT NULL COMMENT '보낼 Kafka 토픽 이름',
    message_key VARCHAR(255) NOT NULL COMMENT 'Kafka 메시지 키 (업무 식별자, 예: orderId). 같은 키는 같은 파티션으로 가서 순서가 지켜짐',
    payload MEDIUMTEXT NOT NULL COMMENT '보낼 메시지 본문 (JSON). 기록한 시점의 값이 그대로 나감',
    status VARCHAR(20) NOT NULL COMMENT '발송 상태 (PENDING: 보낼 차례, SENT: 보냄)',
    attempt_count INT NOT NULL COMMENT '실패한 발송 시도 횟수',
    last_error VARCHAR(500) NULL COMMENT '마지막 발송 실패 원인',
    sent_at DATETIME(6) NULL COMMENT 'Kafka 발송 성공 시각',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 아직 보내지 않은 줄을 쌓인 순서(id)대로 읽는 조회용. 보낸 줄이 쌓여도 PENDING만 훑게 한다
CREATE INDEX idx_order_outbox_status_id ON order_outbox (status, id);
