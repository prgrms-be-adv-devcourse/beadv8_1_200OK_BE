CREATE TABLE product_stock_partial_release (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_id BIGINT NOT NULL COMMENT '환불 모듈 ID',
    order_id BIGINT NOT NULL COMMENT '주문 모듈 ID',
    product_option_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_product_stock_partial_release_refund_id UNIQUE (refund_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
