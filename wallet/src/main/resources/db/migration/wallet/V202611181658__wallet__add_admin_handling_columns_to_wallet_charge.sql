-- 복구가 끝내지 못한 충전 건을 관리자가 처리하려고, 처리할 건과 결제 취소 여부를 남긴다.
ALTER TABLE wallet_charge
    ADD COLUMN approved_after_failure_at DATETIME(6) NULL COMMENT '실패로 처리된 뒤 토스가 승인한 것을 안 시각. 돈은 빠졌는데 잔액이 늘지 않아 관리자가 결제를 취소할 대상',
    ADD COLUMN payment_canceled_at DATETIME(6) NULL COMMENT '관리자가 토스 결제를 취소(환불)한 시각. 상태는 실패로 둔다';
