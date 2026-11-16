-- 지갑이 Kafka로 받은 메시지 중 다시 시도해도 처리하지 못한 메시지를 원문과 함께 남긴다.
-- 실패 토픽 대신 DB에 두어 Kafka가 멈춰도 남고, 같은 토픽을 받는 다른 모듈에 다시 보내지 않으며, SQL로 바로 찾는다(#579).
CREATE TABLE wallet_message_failure (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic VARCHAR(249) NOT NULL COMMENT 'Kafka 토픽 이름',
    kafka_partition INT NOT NULL COMMENT 'Kafka 파티션 번호',
    kafka_offset BIGINT NOT NULL COMMENT 'Kafka 파티션 안의 메시지 위치',
    message_key VARCHAR(255) NULL COMMENT 'Kafka 메시지 키 (업무 식별자, 예: payoutId)',
    payload MEDIUMTEXT NULL COMMENT '메시지 원문 (UTF-8로 읽은 값)',
    failure_reason VARCHAR(1000) NOT NULL COMMENT '마지막 실패의 예외 종류와 메시지',
    status VARCHAR(20) NOT NULL COMMENT '처리 상태 (PENDING: 대기, RESOLVED: 해결)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_wallet_message_failure_topic_kafka_partition_kafka_offset UNIQUE (topic, kafka_partition, kafka_offset)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
