-- 판매자 출고지 우편번호 (#587): delivery가 배송비 계산용 사본을 만들도록 등록·수정 때 Kafka로 보낸다
-- 이 컬럼 전에 만든 프로필은 값이 없어 NULL을 허용한다. 등록·수정 요청에서는 필수라, 기존 판매자는 다음 수정 때 채운다
ALTER TABLE seller_profile
    ADD COLUMN origin_postal_code VARCHAR(5) NULL COMMENT '출고지 우편번호 (5자리 숫자)' AFTER customer_service_phone;
