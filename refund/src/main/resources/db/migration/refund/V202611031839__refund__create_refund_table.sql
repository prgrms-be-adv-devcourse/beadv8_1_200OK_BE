-- 환불 요청 (1단계: 출고 전 취소). 주문 상품 한 줄을 개수 단위로 취소하는 요청 한 건이 한 행이다.
-- 지우지 않고 상태로만 끝내므로 deleted_at을 두지 않는다.
-- 이 파일은 요청(#408)에 필요한 칸만 만든다. 승인·거절(#409), 배송비(#410), 실패 기록(#412) 칸은 각 이슈에서 더한다
CREATE TABLE refund (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL COMMENT '주문 모듈 주문 ID',
    order_item_id BIGINT NOT NULL COMMENT '주문 모듈 주문 상품 ID',
    quantity INT NOT NULL COMMENT '취소 개수',
    buyer_id BIGINT NOT NULL COMMENT '회원 모듈 구매자 ID',
    seller_id BIGINT NOT NULL COMMENT '회원 모듈 판매자 ID (요청 때 주문에서 받음)',
    product_option_id BIGINT NOT NULL COMMENT '상품 모듈 옵션 ID (재고를 되돌릴 때 씀, 요청 때 주문에서 받음)',
    product_name VARCHAR(255) NOT NULL COMMENT '요청 때 주문에서 받은 상품명 (목록 표시용)',
    option_name VARCHAR(255) NOT NULL COMMENT '요청 때 주문에서 받은 옵션명 (목록 표시용)',
    unit_price BIGINT NOT NULL COMMENT '주문 시점 단가 (원, 요청 때 주문에서 받음)',
    item_amount BIGINT NOT NULL COMMENT '돌려줄 상품 금액 = 단가 x 개수 (원)',
    cancel_reason VARCHAR(255) NULL COMMENT '구매자가 적은 취소 사유 (선택 입력, 안 적으면 NULL)',
    status VARCHAR(20) NOT NULL COMMENT '환불 상태 (REQUESTED 등)',
    active_order_item_id BIGINT NULL COMMENT '진행 중일 때만 주문 상품 ID, 끝나면 NULL. 한 주문 상품에 진행 중인 요청을 한 건만 받기 위한 칸',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_active_order_item_id UNIQUE (active_order_item_id),
    CONSTRAINT ck_refund_quantity CHECK (quantity >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
