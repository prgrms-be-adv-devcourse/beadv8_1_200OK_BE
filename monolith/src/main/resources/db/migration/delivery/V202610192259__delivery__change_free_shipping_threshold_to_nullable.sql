-- 판매자 설정에 무료배송이 없는 경우를 결제 시점 값으로 남기려고 null을 허용한다 (#122)
-- ck_shipment_fee의 free_shipping_threshold >= 0 조건은 null이면 UNKNOWN이 되어 통과하므로 제약은 그대로 둔다
ALTER TABLE shipment
    MODIFY COLUMN free_shipping_threshold INT NULL COMMENT '무료배송 기준 금액 (결제 시점), null이면 무료배송 없음';
