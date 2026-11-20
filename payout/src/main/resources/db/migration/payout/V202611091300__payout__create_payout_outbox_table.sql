-- 정산 outbox (#309): Kafka로 보낼 정산 1건당 1줄
-- 정산 확정과 같은 트랜잭션에서 PENDING으로 저장하고, 발송 단계가 Kafka로 보낸 뒤 SENT로 바꿈
-- 메시지 본문은 저장하지 않음 (payout은 바뀌지 않으므로 보낼 때 payout에서 읽음)
CREATE TABLE payout_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payout_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL COMMENT '발송 상태 (PayoutOutboxStatus)',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '실패한 발송 시도 횟수',
    last_error VARCHAR(500) NULL COMMENT '마지막 발송 실패 원인',
    sent_at DATETIME(6) NULL COMMENT 'Kafka 발송 성공 시각',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 같은 정산이 두 줄 생기지 않게 함
    CONSTRAINT uk_payout_outbox_payout_id UNIQUE (payout_id),
    CONSTRAINT fk_payout_outbox_payout_id FOREIGN KEY (payout_id) REFERENCES payout (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 아직 보내지 않은 줄을 덜 시도한 줄부터(같으면 id 순) 읽는 조회용
CREATE INDEX idx_payout_outbox_status_attempt_count_id ON payout_outbox (status, attempt_count, id);
