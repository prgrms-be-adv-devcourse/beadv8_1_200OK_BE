-- 배송지 변경 요청에서 구매자 본인의 shipment인지 확인하려고 구매자 회원 ID를 남긴다 (#199)
-- 결제 완료 이벤트를 발행하는 코드가 아직 없어 기존 행이 없으므로 기본값 없이 NOT NULL로 추가한다
ALTER TABLE shipment
    ADD COLUMN buyer_id BIGINT NOT NULL COMMENT '구매자 회원 ID (member 모듈)' AFTER order_id;
