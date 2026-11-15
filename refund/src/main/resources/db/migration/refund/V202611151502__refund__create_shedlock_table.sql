-- 다시 진행 알람 잠금(#576): 서버가 여러 대여도 알람이 한 번에 한 서버만 돌게 ShedLock이 이 표에 잠금 행을 적는다.
-- ShedLock이 정하는 표 형식 그대로라 엔티티가 없고, BaseTime 칸(created_at 등)도 없다.
-- 시각 칸을 DATETIME(3)으로 두는 이유: TIMESTAMP는 세션 시간대에 따라 값이 달라져 서버마다 잠금 만료가 어긋날 수 있다.
-- 잠금을 손으로 풀 때는 행을 지우지 말고 만료시킨다(지우면 행이 있다고 기억하는 서버는 재시작 전까지 잠금을 못 잡는다)
--   UPDATE paldogotgan_refund.shedlock SET lock_until = UTC_TIMESTAMP(3) WHERE name = 'refundResume';
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL COMMENT '잠금 이름 (refundResume 다시 진행 알람)',
    lock_until DATETIME(3) NOT NULL COMMENT '잠금이 풀리는 시각(UTC). 이 시각이 지나면 다른 서버가 잡을 수 있다',
    locked_at DATETIME(3) NOT NULL COMMENT '잠근 시각(UTC)',
    locked_by VARCHAR(255) NOT NULL COMMENT '잠근 서버 이름',
    PRIMARY KEY (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 잠금 행을 미리 넣어 둔다: 처음 잠글 때 INSERT가 일시 오류(교착·잠금 대기 초과)로 실패해도 ShedLock은 행이 있다고 기억하고
-- UPDATE만 하므로, 행이 없으면 그 서버는 재시작 전까지 잠금을 못 잡는다. lock_until을 지금으로 두어 풀린 상태로 시작한다
INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
VALUES ('refundResume', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'migration');
