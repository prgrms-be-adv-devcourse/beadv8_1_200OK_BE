-- 주문이 결제 요청 뒤 30분 넘게 결과를 받지 못하면 결제 만료를 요청한다. 지갑은 처음 보는 주문이면 만료로 기록해,
-- 뒤늦게 온 결제 요청이 돈을 빼지 못하게 한다. 타입과 제약은 그대로 두고 설명만 바꾼다
ALTER TABLE wallet_order_payment
    MODIFY COLUMN status VARCHAR(20) NOT NULL COMMENT '처리 결과 (PAID: 차감 완료, FAILED: 잔액 부족, EXPIRED: 결제 만료로 결제하지 않기로 함)';
