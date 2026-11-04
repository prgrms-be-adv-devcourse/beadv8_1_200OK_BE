-- 배송 요청사항은 선택 항목이라 NULL을 허용한다. 길이는 배송 모듈 shipment.delivery_note와 맞춘다
ALTER TABLE orders
    ADD COLUMN delivery_note VARCHAR(255) NULL COMMENT '배송 요청사항' AFTER address_detail;
