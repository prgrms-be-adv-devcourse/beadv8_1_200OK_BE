-- 판매자 신청에 출고지 우편번호 추가 (#587): 승인 때 delivery에 처음 값을 보내 판매자가 되자마자 출고지가 있게 한다
-- 승인 뒤 변경은 판매자 프로필(seller_profile.origin_postal_code)에서 한다. 이 값은 신청 당시의 처음 값 기록이다
-- 이 컬럼 전에 한 신청은 값이 없어 NULL을 허용한다. 신청 요청에서는 필수다
ALTER TABLE seller_application
    ADD COLUMN origin_postal_code VARCHAR(5) NULL COMMENT '출고지 우편번호 (5자리 숫자, 신청 당시 값)' AFTER mail_order_number;
