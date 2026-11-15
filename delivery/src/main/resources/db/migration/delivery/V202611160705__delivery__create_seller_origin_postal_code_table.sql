-- 판매자 출고지 우편번호 (원본은 member, Kafka로 받은 복제본. 판매자당 1행)
CREATE TABLE seller_origin_postal_code (
    seller_id BIGINT NOT NULL COMMENT '판매자 ID',
    postal_code VARCHAR(5) NULL COMMENT '출고지 우편번호 (숫자 5자리). NULL이면 출고지 없음',
    source_occurred_at DATETIME(6) NOT NULL COMMENT '복제한 메시지의 발생 시각. 이보다 오래된 메시지는 버린다',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (seller_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
