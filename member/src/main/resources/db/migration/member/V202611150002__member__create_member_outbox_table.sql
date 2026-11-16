-- 회원 outbox (#560): 다른 서비스로 보낼 회원 메시지 1건당 1줄
-- 회원가입과 같은 트랜잭션에서 PENDING으로 저장하고, 발송 작업이 Kafka로 보낸 뒤 SENT로 바꿈
-- 메시지 본문은 저장하지 않음 (지금 보내는 회원가입 메시지는 member_id만 담으므로 보낼 때 만듦)
CREATE TABLE member_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(50) NOT NULL COMMENT '보낼 메시지 종류 (MemberOutboxEventType)',
    member_id BIGINT NOT NULL COMMENT '메시지 키. 같은 회원의 메시지는 같은 파티션으로 감',
    status VARCHAR(20) NOT NULL COMMENT '발송 상태 (MemberOutboxStatus)',
    attempt_count INT NOT NULL COMMENT '실패한 발송 시도 횟수',
    last_error VARCHAR(500) NULL COMMENT '마지막 발송 실패 원인',
    sent_at DATETIME(6) NULL COMMENT 'Kafka 발송 성공 시각',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_member_outbox_member_id FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 아직 보내지 않은 줄을 쌓인 순서(id)대로 읽는 조회용. 30초마다 돌아 보낸 줄이 쌓여도 PENDING만 훑게 함
CREATE INDEX idx_member_outbox_status_id ON member_outbox (status, id);
