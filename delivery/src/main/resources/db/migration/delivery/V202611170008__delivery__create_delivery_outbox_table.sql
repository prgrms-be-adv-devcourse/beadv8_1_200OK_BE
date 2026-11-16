-- 배송 outbox (#644): 다른 서비스로 보낼 배송 메시지 1건당 1줄
-- 출고와 같은 트랜잭션에서 PENDING으로 저장하고, 발송 작업이 Kafka로 보낸 뒤 SENT로 바꿈
CREATE TABLE delivery_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(50) NOT NULL COMMENT '보낼 메시지 종류 (DeliveryOutboxEventType)',
    message_key VARCHAR(100) NOT NULL COMMENT 'Kafka 메시지 키 (주문 모듈 ID). 같은 주문의 메시지는 같은 파티션으로 감',
    payload TEXT NOT NULL COMMENT '보낼 메시지 본문 (JSON)',
    status VARCHAR(20) NOT NULL COMMENT '발송 상태 (DeliveryOutboxStatus)',
    attempt_count INT NOT NULL COMMENT '실패한 발송 시도 횟수',
    last_error VARCHAR(500) NULL COMMENT '마지막 발송 실패 원인',
    sent_at DATETIME(6) NULL COMMENT 'Kafka 발송 성공 시각',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 보낸 줄이 쌓여도 PENDING만 쌓인 순서(id)대로 훑게 함
CREATE INDEX idx_delivery_outbox_status_id ON delivery_outbox (status, id);
