-- 관리자가 토스 결제를 취소하는 동안 같은 충전 건이 잔액에 반영되지 않게, 토스를 부르기 전에 취소 요청을 남긴다.
ALTER TABLE wallet_charge
    ADD COLUMN payment_cancel_requested_at DATETIME(6) NULL COMMENT '관리자가 토스 결제 취소를 요청한 시각. 값이 있는 처리 중 건은 잔액 반영과 실패 처리를 막는다';
