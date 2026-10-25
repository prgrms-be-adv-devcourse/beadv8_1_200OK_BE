-- 판매자 배송 설정 (판매자당 1개. 행이 없는 판매자는 설정 파일의 기본값을 쓴다)
CREATE TABLE seller_shipping_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    seller_id BIGINT NOT NULL COMMENT '판매자 ID',
    base_shipping_fee INT NOT NULL COMMENT '기본 배송비 (원)',
    free_shipping_threshold INT NULL COMMENT '무료배송 기준 금액 (원). NULL이면 무료배송 없음, 0이면 항상 무료',
    cutoff_time TIME NOT NULL COMMENT '출고 마감 시각 (이 시각 전 결제는 당일 출고)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_seller_shipping_profile_seller_id UNIQUE (seller_id),
    CONSTRAINT ck_seller_shipping_profile_base_shipping_fee CHECK (base_shipping_fee >= 0),
    CONSTRAINT ck_seller_shipping_profile_free_shipping_threshold CHECK (free_shipping_threshold >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
