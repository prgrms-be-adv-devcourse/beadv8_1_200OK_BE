-- 지갑 차감 금액 충돌 기록 (#391): 이미 차감 기록이 있는 주문에, 저장된 금액과 다른 금액의 지갑 차감 이벤트가 다시 들어온 사실
-- 차감 기록(payout_wallet_deduction)은 바꾸지 않고 이 표에만 남김. 초과 청구 실패 상세에서 "지갑 금액 충돌 있음"의 근거로 씀
-- INSERT 전용 (수정·삭제 없음)
CREATE TABLE payout_wallet_deduction_conflict (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL COMMENT '주문 ID (order 모듈)',
    stored_amount BIGINT NOT NULL COMMENT '충돌을 기록할 때 저장되어 있던 차감 금액 (원)',
    received_amount BIGINT NOT NULL COMMENT '새로 받은 이벤트의 차감 금액 (원)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 같은 충돌 이벤트가 재전달돼도 1건만 저장 (앞의 order_id가 주문 단위 조회 인덱스도 겸함)
    CONSTRAINT uk_payout_wallet_deduction_conflict_order_id_received_amount UNIQUE (order_id, received_amount)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
