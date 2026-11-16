-- 회원 outbox에 메시지 본문 칸 추가 (#587)
-- 출고지 우편번호처럼 바뀌는 값을 보내는 메시지는 기록한 시점의 값을 그대로 보내야 해서 본문을 저장한다
-- 회원가입 메시지는 회원 ID만 보내 본문이 없다(NULL)
ALTER TABLE member_outbox
    ADD COLUMN payload VARCHAR(1000) NULL COMMENT '보낼 메시지 본문 (JSON). 회원 ID만 보내는 메시지는 NULL' AFTER member_id;
