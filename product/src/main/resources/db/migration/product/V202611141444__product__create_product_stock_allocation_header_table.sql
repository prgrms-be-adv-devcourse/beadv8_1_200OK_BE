-- 주문 단위 재고 배정 상태
-- 같은 주문의 배정·해제는 이 행 잠금에서 차례로 처리된다. 해제가 배정보다 먼저 와도 RELEASED로 남아 이후 배정을 막는다
CREATE TABLE product_stock_allocation_header (
    order_id   BIGINT      NOT NULL COMMENT '주문 모듈 주문 ID',
    status     VARCHAR(20) NOT NULL COMMENT 'ALLOCATED, RELEASED',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
