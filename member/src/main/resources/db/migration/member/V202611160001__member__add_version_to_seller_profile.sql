-- 판매자 프로필 낙관적 락 (#587): 두 탭에서 동시에 수정하면 나중 요청이 모든 칸을 덮어써 출고지를 되돌리고,
-- 우편번호가 바뀌지 않았다고 보고 메시지도 보내지 않아 delivery 사본과 어긋난다. 나중 커밋을 버전으로 막는다
ALTER TABLE seller_profile
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '낙관적 락 버전. 판매자가 같은 프로필을 동시에 수정하는 것을 막는다';
