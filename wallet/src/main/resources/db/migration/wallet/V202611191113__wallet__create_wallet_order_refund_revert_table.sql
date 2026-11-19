-- 환불이 요청한 되돌리기(돌려준 예치금 다시 가져오기)를 지갑이 처리한 결과. 환불 하나에 한 건만 남고 바뀌지 않는다.
-- 실패도 기록해, 같은 되돌리기가 다시 와도 처음 결과를 다시 보내고 돈을 다시 가져오지 않는다.
-- 구매자와 금액은 요청 값이 아니라 반환 처리 기록(wallet_order_refund)의 값이다. 실제로 돈을 받은 지갑에서 가져와야 해서다.
CREATE TABLE wallet_order_refund_revert (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_id BIGINT NOT NULL COMMENT '환불 모듈 ID',
    order_id BIGINT NOT NULL COMMENT '주문 모듈 ID',
    buyer_id BIGINT NOT NULL COMMENT '구매자 회원 ID (반환 처리 기록의 값)',
    amount BIGINT NOT NULL COMMENT '되돌릴 금액 (원, 반환 처리 기록의 값)',
    status VARCHAR(20) NOT NULL COMMENT '처리 결과 (REVERTED: 다시 가져옴 또는 돌려준 적 없음, FAILED: 가져오지 못함)',
    failure_reason VARCHAR(50) NULL COMMENT '실패 사유 (FAILED일 때만)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_wallet_order_refund_revert_refund_id UNIQUE (refund_id),
    CONSTRAINT ck_wallet_order_refund_revert_amount CHECK (amount > 0),
    INDEX idx_wallet_order_refund_revert_order_id (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
