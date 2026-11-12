-- 환불 모듈이 승인 직후 알려 준 주문 상품 취소 반영 기록 (환불 한 건 = 한 줄). 주문 상품 상태는 바꾸지 않고 수량만 남긴다
CREATE TABLE order_item_refund (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    order_item_id         BIGINT      NOT NULL,
    refund_id             BIGINT      NOT NULL COMMENT '환불 모듈 환불 ID. 같은 환불은 한 번만 반영한다',
    quantity              INT         NOT NULL COMMENT '환불 수량',
    deducted_shipping_fee BIGINT      NOT NULL COMMENT '환불 금액에서 뺀 배송비 (원). 구매확정 때 판매자 몫 배송비에 더한다',
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_item_refund_refund_id UNIQUE (refund_id),
    CONSTRAINT ck_order_item_refund_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_item_refund_deducted_shipping_fee CHECK (deducted_shipping_fee >= 0),
    CONSTRAINT fk_order_item_refund_order_item_id FOREIGN KEY (order_item_id) REFERENCES order_item (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
