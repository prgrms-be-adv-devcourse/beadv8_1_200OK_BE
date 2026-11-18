-- 회수 줄(WALLET_REVERT)이 다시 가져올 것으로 기대하는 구매자·금액(#572).
-- 지갑이 환불과 다른 구매자·금액으로 "돌려줬다"고 알리면 그 값을 여기 남기고, 회수 요청에도 이 값을 담는다.
-- 지갑은 자기 반환 기록으로 회수하고 결과에도 그 값을 넣으므로(지갑 #611), 회수 결과는 환불 값이 아니라 이 값과 비교해야 한다. 환불 행에는 이 값이 없다.
-- 결과 불일치로 시작한 회수 줄에만 있다. 재고 거절로 시작한 회수 줄과 다른 단계 줄은 NULL이다(그때는 환불 값과 비교).
-- 줄을 만들 때 정해지고 바뀌지 않는다. 한쪽만 있으면 비교할 값이 모자라 두 칸을 함께 채우거나 함께 비운다
ALTER TABLE refund_step
    ADD COLUMN revert_buyer_id BIGINT NULL COMMENT '결과 불일치 회수로 다시 가져올 구매자 ID(회원 모듈 ID, 지갑이 알린 값). 결과 불일치 회수 줄이 아니면 NULL'
        AFTER awaiting_since,
    ADD COLUMN revert_amount BIGINT NULL COMMENT '결과 불일치 회수로 다시 가져올 금액(원, 지갑이 알린 값). 결과 불일치 회수 줄이 아니면 NULL'
        AFTER revert_buyer_id,
    ADD CONSTRAINT ck_refund_step_revert_buyer_id_revert_amount
        CHECK ((revert_buyer_id IS NULL) = (revert_amount IS NULL));
