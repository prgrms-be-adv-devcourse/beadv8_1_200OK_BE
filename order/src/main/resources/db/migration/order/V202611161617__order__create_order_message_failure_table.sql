-- 주문이 Kafka로 받은 메시지 중 다시 시도해도 처리하지 못한 메시지를 원문과 함께 남긴다 (#666)
-- 실패 토픽 대신 DB에 두어 Kafka가 멈춰도 남고, 같은 토픽을 받는 다른 모듈에 다시 보내지 않으며, SQL로 바로 찾는다(지갑 #579와 같은 방식)
CREATE TABLE order_message_failure (
    id BIGINT NOT NULL AUTO_INCREMENT,
    consumer_group VARCHAR(255) NOT NULL COMMENT '처리하지 못한 리스너의 컨슈머 그룹 (예: order.cart-create)',
    topic VARCHAR(249) NOT NULL COMMENT 'Kafka 토픽 이름',
    kafka_partition INT NOT NULL COMMENT 'Kafka 파티션 번호',
    kafka_offset BIGINT NOT NULL COMMENT 'Kafka 파티션 안의 메시지 위치',
    message_key VARCHAR(255) NULL COMMENT 'Kafka 메시지 키 (업무 식별자, 예: orderId)',
    payload MEDIUMTEXT NULL COMMENT '메시지 원문 (UTF-8로 읽은 값)',
    failure_reason VARCHAR(1000) NOT NULL COMMENT '마지막 실패의 예외 종류와 메시지',
    status VARCHAR(20) NOT NULL COMMENT '처리 상태 (PENDING: 대기, RESOLVED: 해결)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 같은 메시지를 같은 리스너가 다시 실패해도 한 번만 남긴다. 주문 안의 다른 리스너가 같은 토픽을 받으면 따로 남긴다.
    -- 컬럼 이름을 모두 쓰면 MySQL 이름 길이 제한(64자)을 넘어 컬럼 이름을 줄였다
    CONSTRAINT uk_order_message_failure_group_topic_partition_offset
        UNIQUE (consumer_group, topic, kafka_partition, kafka_offset)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
