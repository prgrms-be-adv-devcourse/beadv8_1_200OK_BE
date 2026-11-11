-- 정산 Job 동시 실행 잠금 (#223): ShedLock이 정하는 표 형식 그대로라 엔티티가 없고, BaseTime 칸(created_at 등)도 없음
-- 시각 칸을 DATETIME(3)으로 둔 이유: TIMESTAMP는 세션 시간대에 따라 값이 달라져 서버마다 잠금 만료가 어긋나 잠금이 뚫릴 수 있음
-- 잠금을 손으로 풀 때는 행을 DELETE하지 말고 아래처럼 만료시킴
--   UPDATE paldogotgan_payout.shedlock SET lock_until = UTC_TIMESTAMP(3) WHERE name = 'payoutJob';
-- DELETE하면 ShedLock이 행이 있다고 기억하는 서버는 재시작 전까지 잠금을 다시 못 잡음
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until DATETIME(3) NOT NULL,
    locked_at DATETIME(3) NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 잠금 행을 미리 넣어 둠: 서버가 처음 잠글 때 INSERT가 일시 오류(교착·잠금 대기 초과)로 실패해도
-- ShedLock은 행이 있다고 기억하고 UPDATE만 하므로, 행이 없으면 그 서버는 재시작 전까지 잠금을 못 잡음
-- lock_until을 지금으로 두어 처음부터 풀린 상태로 시작함
INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
VALUES ('payoutJob', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'migration');
