-- 지갑이 처리한 주문 결제 요청의 결과. 주문 하나에 한 건만 남고 바뀌지 않는다.
-- 성공뿐 아니라 잔액 부족 실패도 기록해, 같은 결제 요청이 재전달되어도 처음 결과를 유지하고 다시 차감하지 않는다.
CREATE TABLE wallet_order_payment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL COMMENT '주문 모듈 ID',
    buyer_id BIGINT NOT NULL COMMENT '구매자 회원 ID',
    amount BIGINT NOT NULL COMMENT '결제 금액 (원)',
    status VARCHAR(20) NOT NULL COMMENT '처리 결과 (PAID: 차감 완료, FAILED: 잔액 부족)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_wallet_order_payment_order_id UNIQUE (order_id),
    CONSTRAINT ck_wallet_order_payment_amount CHECK (amount > 0)
);
