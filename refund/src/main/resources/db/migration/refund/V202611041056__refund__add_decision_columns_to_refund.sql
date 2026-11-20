-- 판매자 승인·거절과 구매자 철회(#409)에 쓰는 칸. 요청됨인 동안에는 둘 다 정해지지 않아 NULL을 허용한다
ALTER TABLE refund
    ADD COLUMN reject_reason VARCHAR(255) NULL COMMENT '판매자가 적은 거절 사유 (거절일 때만)' AFTER status,
    ADD COLUMN decided_at DATETIME(6) NULL COMMENT '판매자가 승인·거절하거나 구매자가 철회한 시각 (요청됨이면 NULL)' AFTER reject_reason;
