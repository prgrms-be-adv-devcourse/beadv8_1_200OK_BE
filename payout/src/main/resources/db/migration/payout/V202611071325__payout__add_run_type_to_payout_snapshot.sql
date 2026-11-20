-- 정산 실행 경로(SCHEDULED: 정기, MANUAL: 관리자 실행, SKIP_WAIT: 대기 건너뛰기)를 스냅샷에 남긴다 (#485)
-- 기존 행은 실행 경로를 알 수 없어 SCHEDULED로 채우고, 값을 빠뜨린 INSERT가 조용히 정기 실행으로 기록되지 않게 DEFAULT를 지운다
ALTER TABLE payout_snapshot
    ADD COLUMN run_type VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED' COMMENT '실행 유형(SCHEDULED, MANUAL, SKIP_WAIT)';
ALTER TABLE payout_snapshot
    ALTER COLUMN run_type DROP DEFAULT;
