-- 지갑에 결제를 요청한 시각(주문 생성 이벤트 발행 시각). 비어 있으면 아직 만드는 중인 주문이다(#402)
-- 오래된 결제 대기 주문 정리가 "결제 요청 전에 멈춘 주문"과 "결제 결과를 기다리다 멈춘 주문"을 이 값으로 구분한다
ALTER TABLE orders
    ADD COLUMN payment_requested_at DATETIME(6) NULL COMMENT '지갑에 결제를 요청한 시각, NULL이면 결제 요청 전' AFTER status,
    ADD INDEX idx_orders_status_payment_requested_at (status, payment_requested_at);

-- 이미 있는 결제 대기 주문은 결제를 요청했는지 알 수 없다. 요청한 것으로 보고 생성 시각을 넣는다.
-- 요청 전으로 두면 정리 뒤 늦게 온 결제를 놓칠 수 있고, 요청한 것으로 두면 정리 기준만 길어진다(안전한 쪽)
UPDATE orders
SET payment_requested_at = created_at
WHERE status = 'PAYMENT_PENDING';
