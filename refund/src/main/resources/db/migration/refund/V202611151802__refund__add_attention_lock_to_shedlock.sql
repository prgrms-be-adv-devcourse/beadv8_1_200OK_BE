-- 멈춘 환불 찾기 알람 잠금(#577): 다시 진행 알람(refundResume)과 같은 표에 잠금 이름만 다르게 쓴다.
-- 잠금 행을 미리 넣는 이유는 refundResume과 같다: 처음 잠글 때 INSERT가 일시 오류로 실패해도 ShedLock은 행이 있다고 기억하고
-- UPDATE만 하므로, 행이 없으면 그 서버는 재시작 전까지 잠금을 못 잡는다. lock_until을 지금으로 두어 풀린 상태로 시작한다
INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
VALUES ('refundAttention', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'migration');
