-- 취소 승인과 송장 등록이 동시에 처리되지 않도록, 환불이 취소 승인 시점에 걸어 두는 예약 수량을 남긴다 (#184)
-- 예약된 항목이 있으면 송장 등록을 거절하고, 취소 확정 이벤트를 받으면 취소 수량으로 옮기며 해제한다
ALTER TABLE shipment_item
    ADD COLUMN reserved_cancel_quantity INT NOT NULL DEFAULT 0 COMMENT '취소 확정 예약 수량 (환불이 취소 승인 시 예약, 취소 확정 이벤트로 해제)' AFTER canceled_quantity,
    ADD CONSTRAINT ck_shipment_item_reserved_cancel_quantity CHECK (reserved_cancel_quantity >= 0 AND canceled_quantity + reserved_cancel_quantity <= quantity);
