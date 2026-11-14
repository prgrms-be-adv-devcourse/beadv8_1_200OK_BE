-- 정산 실패 해결 기록 (#568): 관리자가 잠기거나 멈춘 주문을 "다시 처리"로 해결한 기록
-- 실패 기록(payout_failure)은 지우거나 고치지 않고, 해결한 실패 기록마다 이 표에 1건을 남김
--   해결 기록이 있는 실패는 잠금 조건과 #549 실패 횟수에서 빠짐 → 다음 배치에서 다시 검사됨
-- INSERT 전용 (수정·삭제 없음). 되돌리기는 없음: 잘못 해결하면 다시 검사에서 생긴 새 실패 기록이 다시 잠그거나 멈춤
CREATE TABLE payout_failure_resolution (
    id BIGINT NOT NULL AUTO_INCREMENT,
    failure_id BIGINT NOT NULL COMMENT '해결한 실패 기록 ID',
    order_id BIGINT NOT NULL COMMENT '주문 ID (order 모듈). 실패 기록에서 복사',
    order_item_id BIGINT NULL COMMENT '주문상품 ID (order 모듈). 실패 기록에서 복사, 배송비 실패는 NULL',
    method VARCHAR(30) NOT NULL COMMENT '해결 방법 (PayoutResolutionMethod). 지금은 REPROCESS만',
    admin_id BIGINT NOT NULL COMMENT '해결한 관리자 회원 ID (member 모듈)',
    memo VARCHAR(255) NOT NULL COMMENT '해결 메모',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 실패 기록 하나는 한 번만 해결됨. 같은 주문을 동시에 두 번 해결하면 두 번째가 여기서 막힘(409로 응답)
    CONSTRAINT uk_payout_failure_resolution_failure_id UNIQUE (failure_id),
    CONSTRAINT fk_payout_failure_resolution_failure_id FOREIGN KEY (failure_id) REFERENCES payout_failure (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 주문 단위 조회용 (해결된 주문 다시 넣기, 해결 기록 조회 #571)
CREATE INDEX idx_payout_failure_resolution_order_id ON payout_failure_resolution (order_id);
