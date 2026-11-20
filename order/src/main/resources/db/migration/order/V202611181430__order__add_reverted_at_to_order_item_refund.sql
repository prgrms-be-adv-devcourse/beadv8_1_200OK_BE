-- 환불이 승인 뒤 끝내 실패해 취소 기록을 되돌렸으면 그 시각을 남긴다(#604)
-- 줄을 지우지 않고 표시만 하는 이유: 같은 되돌리기가 다시 오거나 되돌린 환불의 기록 요청이 늦게 와도 이미 되돌렸다는 것을 알아야 한다
-- 되돌린 줄은 환불 수량·뺀 배송비 합계에서 빠진다
ALTER TABLE order_item_refund
    ADD COLUMN reverted_at DATETIME(6) NULL COMMENT '취소 기록을 되돌린 시각, NULL이면 유효한 취소 기록' AFTER deducted_shipping_fee;
