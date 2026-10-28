-- 정산후보: 수동 조정 구분 컬럼 추가 및 UK 변경
ALTER TABLE payout_candidate_items
    ADD COLUMN adjustment_id BIGINT       NOT NULL DEFAULT 0,
    ADD COLUMN admin_id      BIGINT       NULL,
    ADD COLUMN reason        VARCHAR(255) NULL;

ALTER TABLE payout_candidate_items
    DROP INDEX uk_payout_candidate_items_order_item_id_event_type,
    ADD CONSTRAINT uk_payout_candidate_items_order_item_id_event_type_adjustment_id
        UNIQUE (order_item_id, event_type, adjustment_id);

-- 정산 항목: 정산후보 1건 = 정산 항목 1건 (개발 DB 초기화 전제, 기존 데이터 이관 없음)
ALTER TABLE payout_item
    ADD COLUMN candidate_id BIGINT NOT NULL;

ALTER TABLE payout_item
    DROP INDEX uk_payout_item_order_item_id_event_type,
    ADD CONSTRAINT uk_payout_item_candidate_id UNIQUE (candidate_id),
    ADD CONSTRAINT fk_payout_item_candidate_id
        FOREIGN KEY (candidate_id) REFERENCES payout_candidate_items (id);
