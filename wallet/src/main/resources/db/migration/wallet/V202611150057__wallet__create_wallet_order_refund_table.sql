-- 지갑이 처리한 환불 반환 요청의 결과. 환불 하나에 한 건만 남고 바뀌지 않는다.
-- 거절도 기록해, 같은 환불이 다시 와도 처음 결과를 다시 보내고 돈을 다시 주지 않는다.
-- 한 주문을 나눠 환불할 수 있어 주문당 여러 건이 남고, 주문별 반환 합계를 결제 금액과 비교하는 데 쓴다.
CREATE TABLE wallet_order_refund (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_id BIGINT NOT NULL COMMENT '환불 모듈 ID',
    order_id BIGINT NOT NULL COMMENT '주문 모듈 ID',
    buyer_id BIGINT NOT NULL COMMENT '구매자 회원 ID',
    amount BIGINT NOT NULL COMMENT '환불이 요청한 반환 금액 (원)',
    status VARCHAR(20) NOT NULL COMMENT '처리 결과 (COMPLETED: 돌려줌, FAILED: 거절)',
    failure_reason VARCHAR(50) NULL COMMENT '거절 사유 (FAILED일 때만)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_wallet_order_refund_refund_id UNIQUE (refund_id),
    CONSTRAINT ck_wallet_order_refund_amount CHECK (amount > 0),
    INDEX idx_wallet_order_refund_order_id (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
