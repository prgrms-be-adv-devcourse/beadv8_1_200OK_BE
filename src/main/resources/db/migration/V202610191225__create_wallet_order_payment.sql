-- 주문별 예치금 결제 결과. 같은 주문 이벤트가 다시 와도 처음 결과를 유지하도록 주문 ID를 유일하게 둔다.
-- 성공뿐 아니라 잔액 부족 실패도 기록해, 실패한 주문이 재전달 후 충전된 잔액으로 결제되지 않게 한다.
CREATE TABLE wallet_order_payment (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    order_id     BIGINT      NOT NULL,
    buyer_id     BIGINT      NOT NULL,
    amount       BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_wallet_order_payment_order_id UNIQUE (order_id),
    CONSTRAINT ck_wallet_order_payment_amount CHECK (amount > 0)
);
