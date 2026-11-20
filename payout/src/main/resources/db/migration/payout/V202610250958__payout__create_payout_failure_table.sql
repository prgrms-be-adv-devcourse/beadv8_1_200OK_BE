-- 정산 실패 기록 (#194): 이번 정산에서 제외한 정산후보와 그 사유
-- 정산된 것의 장부(payout, payout_item)와 섞지 않으려고 따로 둠
-- INSERT 전용 (수정·삭제 없음)
CREATE TABLE payout_failure (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    candidate_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL COMMENT '주문 ID (order 모듈)',
    order_item_id BIGINT NULL COMMENT '주문상품 ID (order 모듈). 배송비 후보는 NULL',
    seller_id BIGINT NOT NULL COMMENT '판매자 회원 ID (member 모듈)',
    reason VARCHAR(50) NOT NULL COMMENT '실패 사유 (PayoutFailureReason)',
    detail VARCHAR(255) NOT NULL COMMENT '실패 상세 (검사 종류, 기대값·실제값 등)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 한 스냅샷에서 한 후보는 한 번만 실패 기록. draft 제외 조건의 조회 인덱스도 겸함
    CONSTRAINT uk_payout_failure_snapshot_id_candidate_id UNIQUE (snapshot_id, candidate_id),
    CONSTRAINT fk_payout_failure_snapshot_id FOREIGN KEY (snapshot_id) REFERENCES payout_snapshot (id),
    CONSTRAINT fk_payout_failure_candidate_id FOREIGN KEY (candidate_id) REFERENCES payout_candidate_items (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 주문 단위 조회용 (#195 주문 잠금)
CREATE INDEX idx_payout_failure_order_id ON payout_failure (order_id);
