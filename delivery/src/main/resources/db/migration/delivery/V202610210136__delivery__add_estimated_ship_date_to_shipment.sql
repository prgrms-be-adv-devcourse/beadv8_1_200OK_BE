-- 판매자가 출고 예정일을 보고 출고를 준비하므로, 도착 예정일을 계산할 때 쓴 출고 예정일을 함께 남긴다 (#202)
-- 결제 완료 이벤트를 발행하는 코드가 아직 없어 기존 행이 없으므로 기본값 없이 NOT NULL로 추가한다
ALTER TABLE shipment
    ADD COLUMN estimated_ship_date DATE NOT NULL COMMENT '출고 예정일 (결제 시점, 배송지 변경 승인 시 다시 계산)' AFTER free_shipping_threshold,
    ADD CONSTRAINT ck_shipment_estimated_ship_date CHECK (estimated_ship_date <= estimated_delivery_start);
