-- 승인 뒤 단계를 모두 마쳐 완료된 시각(#414). 지금은 승인 요청 안에서 바로 완료돼 decided_at과 거의 같지만,
-- 지갑 완료 메시지를 받은 뒤 완료로 바꾸게 되면(#516) 승인 시각과 달라져 따로 둔다. 완료 전에는 정해지지 않아 NULL을 허용한다
ALTER TABLE refund
    ADD COLUMN completed_at DATETIME(6) NULL COMMENT '승인 뒤 단계를 모두 마쳐 완료된 시각 (완료 전이면 NULL)' AFTER decided_at;
