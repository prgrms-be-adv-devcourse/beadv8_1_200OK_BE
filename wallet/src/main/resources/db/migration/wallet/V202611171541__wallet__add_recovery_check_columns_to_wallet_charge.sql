-- 처리 중으로 멈춘 충전 건을 복구가 토스에 다시 물어보려고 확인 시각과 횟수를 둔다.
ALTER TABLE wallet_charge
    ADD COLUMN next_check_at DATETIME(6) NULL COMMENT '복구가 다음에 토스에 확인할 시각. 처리 중인 건에서만 보고, 비어 있으면 복구가 고르지 않는다(사람 확인 대기)',
    ADD COLUMN check_count INT NOT NULL DEFAULT 0 COMMENT '복구가 토스에 확인한 횟수';

-- 복구는 확인할 때가 된 처리 중 건을 시각 순으로 고른다
CREATE INDEX idx_wallet_charge_status_next_check_at ON wallet_charge (status, next_check_at);
