-- 무료배송 기준·기본 배송비(결제 당시 규칙)와 별도로, 구매자가 실제로 낸 배송비(결과)를 남긴다 (#122)
-- shipment를 만드는 코드(#180)가 아직 없어 기존 행이 없으므로 기본값 없이 NOT NULL로 추가한다
ALTER TABLE shipment
    ADD COLUMN charged_shipping_fee INT NOT NULL COMMENT '실제 부과한 배송비 (결제 시점, 무료배송 적용 후 기본 배송비 + 추가 운임)' AFTER base_shipping_fee,
    ADD CONSTRAINT ck_shipment_charged_shipping_fee CHECK (charged_shipping_fee >= 0);
