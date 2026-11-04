-- 주문 때 배송 모듈이 계산에 쓴 판매자 배송 설정을 남긴다. 결제 완료 이벤트로 배송에 넘겨 같은 규칙으로 배송비를 다시 계산하게 한다
-- 기존 묶음은 배송비를 0원으로 받았으므로 기본 배송비 0, 무료배송 기준 NULL(무료배송 없음)로 채워도 받은 금액과 맞다
ALTER TABLE order_seller_group
    ADD COLUMN base_shipping_fee BIGINT NOT NULL DEFAULT 0 COMMENT '판매자 설정의 기본 배송비 (원). 무료배송이어도 설정 값' AFTER shipping_fee,
    ADD COLUMN free_shipping_threshold BIGINT NULL COMMENT '판매자 설정의 무료배송 기준 금액 (원). NULL이면 무료배송 없음' AFTER base_shipping_fee;
