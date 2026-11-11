-- 정산 Job 동시 실행 잠금 (#223): ShedLock이 정하는 표 형식 그대로라 엔티티가 없고, BaseTime 칸(created_at 등)도 없음
-- 시각 칸을 DATETIME(3)으로 둔 이유: TIMESTAMP는 세션 시간대에 따라 값이 달라져 서버마다 잠금 만료가 어긋나 잠금이 뚫릴 수 있음
-- 잠금을 손으로 풀 때는 행을 DELETE하지 말고 아래처럼 만료시킴
--   UPDATE shedlock SET lock_until = UTC_TIMESTAMP(3) WHERE name = 'payoutJob';
-- DELETE하면 ShedLock이 행이 있다고 기억하는 서버는 재시작 전까지 잠금을 다시 못 잡음
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until DATETIME(3) NOT NULL,
    locked_at DATETIME(3) NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
