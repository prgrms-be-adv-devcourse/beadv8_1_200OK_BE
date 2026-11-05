-- 환불 승인 때 정하는 배송비(#410). 요청됨인 동안에는 아직 정하지 않아 0이다.
-- 값이 없는 것과 0원을 구분할 일이 없어 NULL 대신 0으로 채운다. 돌려줄 금액 합계는 이 칸들로 계산할 수 있어 저장하지 않는다
ALTER TABLE refund
    ADD COLUMN shipping_fee_amount BIGINT NOT NULL DEFAULT 0
        COMMENT '돌려줄 배송비 (원). 판매자 묶음이 전부 취소될 때만 묶음 배송비 + 앞선 환불에서 뺀 배송비 합' AFTER item_amount,
    ADD COLUMN remote_area_fee_amount BIGINT NOT NULL DEFAULT 0
        COMMENT '돌려줄 도서산간 운임 (원). 판매자 묶음이 전부 취소될 때만' AFTER shipping_fee_amount,
    ADD COLUMN deducted_shipping_fee_amount BIGINT NOT NULL DEFAULT 0
        COMMENT '환불 금액에서 뺀 배송비 (원). 일부 취소로 무료배송 기준이 처음 깨질 때만 기본 배송비' AFTER remote_area_fee_amount,
    ADD CONSTRAINT ck_refund_shipping_fee_amount CHECK (shipping_fee_amount >= 0),
    ADD CONSTRAINT ck_refund_remote_area_fee_amount CHECK (remote_area_fee_amount >= 0),
    ADD CONSTRAINT ck_refund_deducted_shipping_fee_amount CHECK (deducted_shipping_fee_amount >= 0);

-- 승인 때 같은 판매자 묶음(주문 ID + 판매자 ID)의 처리 중 요청을 모아 배송비를 판단한다. 그 집계 조회용 인덱스
CREATE INDEX idx_refund_order_id_seller_id ON refund (order_id, seller_id);
