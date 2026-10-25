-- 지갑 차감 기록: 지갑이 주문 결제 금액을 구매자 예치금에서 차감한 사실 (#73)
-- 정산 교차 검증(#67)에서 정산후보와 비교할 원천 데이터. 수신 시점에는 저장만 하고 비교하지 않음
-- INSERT 전용 (수정·삭제 없음)
CREATE TABLE payout_wallet_deduction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL COMMENT '주문 ID (order 모듈)',
    buyer_id BIGINT NOT NULL COMMENT '구매자 회원 ID (member 모듈)',
    amount BIGINT NOT NULL COMMENT '차감 금액 (원)',
    deducted_at DATETIME(6) NOT NULL COMMENT '지갑이 차감한 시각 (WalletPaymentCompletedEvent.paidAt)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 지갑은 주문당 한 번만 차감·발행하지만 이벤트는 재전달될 수 있어 주문당 1건만 저장
    CONSTRAINT uk_payout_wallet_deduction_order_id UNIQUE (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
