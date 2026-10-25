ALTER TABLE member
    RENAME COLUMN role TO member_type;

UPDATE member
SET member_type = 'USER'
WHERE member_type IN ('BUYER', 'SELLER');

ALTER TABLE member
    MODIFY COLUMN member_type VARCHAR(20) NOT NULL
    COMMENT '회원 유형 (USER, ADMIN). 판매자 여부는 판매자 신청 상태로 판단한다';
