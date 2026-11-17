-- 지갑 결과를 처음 기다리기 시작한 시각(#577). 결과 대기는 시간이 지나면 같은 요청을 다시 보내며 next_check_at을 5분 뒤로 밀므로,
-- 처음부터 얼마나 기다렸는지는 이 칸으로 잰다. 멈춘 환불 찾기가 "30분 넘게 기다림"을 이 값으로 판단한다.
-- 결과 대기가 된 적 없는 줄(주문·재고 줄, 보내기 전 지갑 줄)은 채울 값이 없어 NULL을 허용한다
ALTER TABLE refund_step
    ADD COLUMN awaiting_since DATETIME(6) NULL COMMENT '지갑 결과를 처음 기다리기 시작한 시각. 다시 보내도 바뀌지 않는다. 결과 대기가 된 적 없으면 NULL'
        AFTER next_check_at;

-- 배포 전에 이미 결과 대기였던 줄은 처음 시작 시각을 알 수 없어 줄을 만든 시각으로 채운다
UPDATE refund_step
SET awaiting_since = created_at
WHERE status = 'AWAITING_RESULT';
