-- 환불 요청 (1단계: 출고 전 취소). 주문 상품 한 줄의 개수 단위로 요청 한 건이 한 행이다.
-- 지우지 않고 상태로만 끝내므로 deleted_at을 두지 않는다
CREATE TABLE refund (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL COMMENT '주문 모듈 주문 ID',
    order_item_id BIGINT NOT NULL COMMENT '주문 모듈 주문 상품 ID',
    quantity INT NOT NULL COMMENT '취소 개수',
    buyer_id BIGINT NOT NULL COMMENT '회원 모듈 구매자 ID',
    seller_id BIGINT NOT NULL COMMENT '회원 모듈 판매자 ID (주문에서 받음)',
    product_option_id BIGINT NOT NULL COMMENT '상품 모듈 옵션 ID (재고 되돌리기용)',
    product_name VARCHAR(255) NOT NULL COMMENT '요청 때 주문에서 받은 상품명 (목록 표시용)',
    option_name VARCHAR(255) NOT NULL COMMENT '요청 때 주문에서 받은 옵션명 (목록 표시용)',
    unit_price BIGINT NOT NULL COMMENT '단가 (원, 주문에서 받음)',
    item_amount BIGINT NOT NULL COMMENT '단가 x 개수 (원)',
    shipping_fee_amount BIGINT NOT NULL DEFAULT 0 COMMENT '돌려줄 배송비 (원). 승인 때 판매자 묶음 전부 취소일 때만 채운다',
    remote_area_fee_amount BIGINT NOT NULL DEFAULT 0 COMMENT '돌려줄 도서산간 추가 운임 (원). 승인 때 판매자 묶음 전부 취소일 때만 채운다',
    status VARCHAR(20) NOT NULL COMMENT 'REQUESTED, REJECTED, WITHDRAWN, PROCESSING, NOT_APPROVABLE, COMPLETED',
    reject_reason VARCHAR(255) NULL COMMENT '판매자 거절 사유 (거절일 때만)',
    failed_step VARCHAR(30) NULL COMMENT '처리 중에 멈춘 단계 (실패가 있을 때만)',
    failure_code VARCHAR(100) NULL COMMENT '멈춘 단계에서 받은 오류 코드 (실패가 있을 때만)',
    decided_at DATETIME(6) NULL COMMENT '승인·거절·철회 시각',
    completed_at DATETIME(6) NULL COMMENT '완료·승인 불가 시각',
    active_order_item_id BIGINT NULL COMMENT '요청됨·처리 중일 때만 주문 상품 ID. 한 주문 상품에 진행 중인 요청을 한 건만 받기 위한 칸',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_active_order_item_id UNIQUE (active_order_item_id),
    CONSTRAINT ck_refund_quantity CHECK (quantity >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 판매자 목록 (상태 거르기)
CREATE INDEX idx_refund_seller_id_status ON refund (seller_id, status);
-- 구매자 목록 (최신순)
CREATE INDEX idx_refund_buyer_id_created_at ON refund (buyer_id, created_at);
-- 승인 때 같은 판매자 묶음의 환불 행 찾기
CREATE INDEX idx_refund_order_id_seller_id ON refund (order_id, seller_id);
-- 요청 때 주문 상품의 처리 중+완료 수량 합(남은 개수 계산)
CREATE INDEX idx_refund_order_item_id_status ON refund (order_item_id, status);
