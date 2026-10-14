-- 정산 배치 Write-Audit-Publish: 스냅샷(대상 고정) → 임시 정산(draft) → 검증 → payout 확정
-- 모든 테이블은 INSERT 전용 (검증 실패한 draft도 기록으로 남김)

-- 스냅샷: 이번 배치가 맡은 정산 대상 (Job 실행 1회 = 스냅샷 1건)
-- job_execution_id는 BATCH_JOB_EXECUTION을 가리키지만 FK 없음: 배치 실행 기록 정리(삭제)를 막지 않기 위함
CREATE TABLE payout_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    job_execution_id BIGINT NOT NULL COMMENT 'Spring Batch BATCH_JOB_EXECUTION ID',
    period_from DATETIME(6) NULL COMMENT '조회 하한 (포함), NULL이면 하한 없음',
    period_to DATETIME(6) NOT NULL COMMENT '조회 상한 (미포함)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payout_snapshot_job_execution_id UNIQUE (job_execution_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 스냅샷 목록: 정산후보를 복사하지 않고 ID와 금액만 기록 (검증 기준)
CREATE TABLE payout_snapshot_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    candidate_id BIGINT NOT NULL,
    amount BIGINT NOT NULL COMMENT '정산후보 금액 (원)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payout_snapshot_item_snapshot_id_candidate_id UNIQUE (snapshot_id, candidate_id),
    CONSTRAINT fk_payout_snapshot_item_snapshot_id FOREIGN KEY (snapshot_id) REFERENCES payout_snapshot (id),
    CONSTRAINT fk_payout_snapshot_item_candidate_id FOREIGN KEY (candidate_id) REFERENCES payout_candidate_items (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 임시 정산: 판매자별 합계 (판매자 몫만, 시스템 몫 합계는 commitStep에서 계산)
CREATE TABLE payout_seller_draft (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL COMMENT '판매자 회원 ID (member 모듈)',
    amount BIGINT NOT NULL COMMENT '판매자 몫 합계 (원)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payout_seller_draft_snapshot_id_seller_id UNIQUE (snapshot_id, seller_id),
    CONSTRAINT fk_payout_seller_draft_snapshot_id FOREIGN KEY (snapshot_id) REFERENCES payout_snapshot (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 임시 정산 항목: 판매자 몫 (판매 대금, 배송비)
CREATE TABLE payout_seller_draft_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    seller_draft_id BIGINT NOT NULL,
    candidate_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL COMMENT '정산 이벤트 타입 (PayoutEventType)',
    order_item_id BIGINT NOT NULL COMMENT '주문상품 ID (order 모듈)',
    seller_id BIGINT NOT NULL COMMENT '판매자 회원 ID (member 모듈)',
    amount BIGINT NOT NULL COMMENT '금액 (원)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payout_seller_draft_item_snapshot_id_candidate_id UNIQUE (snapshot_id, candidate_id),
    CONSTRAINT fk_payout_seller_draft_item_snapshot_id FOREIGN KEY (snapshot_id) REFERENCES payout_snapshot (id),
    CONSTRAINT fk_payout_seller_draft_item_seller_draft_id FOREIGN KEY (seller_draft_id) REFERENCES payout_seller_draft (id),
    CONSTRAINT fk_payout_seller_draft_item_candidate_id FOREIGN KEY (candidate_id) REFERENCES payout_candidate_items (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 임시 정산 항목: 시스템 몫 (판매 수수료). 여러 판매자분을 합치므로 판매자 단위 합계 없이 항목만 저장
CREATE TABLE payout_system_draft_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    candidate_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL COMMENT '정산 이벤트 타입 (PayoutEventType)',
    order_item_id BIGINT NOT NULL COMMENT '주문상품 ID (order 모듈)',
    seller_id BIGINT NOT NULL COMMENT '수수료가 발생한 판매자 회원 ID (member 모듈)',
    amount BIGINT NOT NULL COMMENT '금액 (원)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payout_system_draft_item_snapshot_id_candidate_id UNIQUE (snapshot_id, candidate_id),
    CONSTRAINT fk_payout_system_draft_item_snapshot_id FOREIGN KEY (snapshot_id) REFERENCES payout_snapshot (id),
    CONSTRAINT fk_payout_system_draft_item_candidate_id FOREIGN KEY (candidate_id) REFERENCES payout_candidate_items (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 정산: 어느 스냅샷에서 확정됐는지 기록 (개발 DB 초기화 전제, 기존 데이터 이관 없음)
-- UK(snapshot_id, payee_id): 같은 스냅샷에서 같은 판매자 정산이 두 번 생기지 않게 막음
--   예) (12, 7) 저장 후 (12, 7) 다시 저장 → 실패
-- 단, 시스템 몫은 payee_id가 NULL이라 이 UK로 막을 수 없음 (MySQL은 NULL끼리 같은 값으로 보지 않음)
--   예) (12, NULL) 저장 후 (12, NULL) 다시 저장 → 성공해 버림
-- 정상 흐름에서는 commitStep이 스냅샷당 시스템 몫을 1건만 만듦
-- 버그·재실행으로 같은 스냅샷을 다시 commit하면 payout_item UK(candidate_id) 위반으로 전체 롤백되어 중복이 남지 않음
-- → commitStep 트랜잭션을 나누면 시스템 몫 중복이 그대로 남으므로 분리 금지
ALTER TABLE payout
    ADD COLUMN snapshot_id BIGINT NOT NULL AFTER id,
    ADD CONSTRAINT uk_payout_snapshot_id_payee_id UNIQUE (snapshot_id, payee_id),
    ADD CONSTRAINT fk_payout_snapshot_id FOREIGN KEY (snapshot_id) REFERENCES payout_snapshot (id);
