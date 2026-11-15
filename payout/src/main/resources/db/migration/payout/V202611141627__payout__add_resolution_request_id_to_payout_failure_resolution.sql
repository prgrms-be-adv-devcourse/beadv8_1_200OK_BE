-- 정산 실패 해결 기록에 해결 요청 번호 추가 (#571)
-- 해결 요청 1번(관리자가 주문 하나를 해결)이 실패 기록마다 해결 기록을 1건씩 남겨서, 같은 요청에서 나온 줄을 묶을 값이 필요함
--   created_at은 줄마다 따로 찍혀(마이크로초) 같은 요청이어도 다를 수 있어 묶는 데 쓸 수 없음
-- 값은 서비스가 해결 요청마다 1번 만드는 UUID 문자열. 아직 운영 데이터가 없어 기존 줄 채우기는 하지 않음
ALTER TABLE payout_failure_resolution
    ADD COLUMN resolution_request_id VARCHAR(36) NOT NULL COMMENT '해결 요청 번호 (UUID). 한 번의 해결 요청으로 생긴 줄은 모두 같음' AFTER id;
