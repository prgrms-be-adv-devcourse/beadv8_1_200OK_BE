-- 정산이 어느 경로로 실행됐는지 기록 (#389)
-- DEFAULT는 이미 있는 행을 채우기 위한 값. 새 행은 항상 애플리케이션이 실행 유형을 넣음
ALTER TABLE payout_snapshot
    ADD COLUMN run_type VARCHAR(50) NOT NULL DEFAULT 'SCHEDULED'
        COMMENT '정산 실행 유형 (PayoutRunType: SCHEDULED, MANUAL, SKIP_WAIT)' AFTER job_execution_id;
