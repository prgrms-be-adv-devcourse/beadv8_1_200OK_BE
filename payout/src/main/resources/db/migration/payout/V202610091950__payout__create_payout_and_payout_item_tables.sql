CREATE TABLE payout
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    payee_id    BIGINT      NOT NULL,
    amount      BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE payout_item
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    payout_id      BIGINT      NOT NULL,
    event_type     VARCHAR(50) NOT NULL,
    order_item_id  BIGINT      NOT NULL,
    seller_id      BIGINT      NOT NULL,
    amount         BIGINT      NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payout_item_order_item_id_event_type UNIQUE (order_item_id, event_type),
    CONSTRAINT fk_payout_item_payout_id FOREIGN KEY (payout_id) REFERENCES payout (id)
);
-- 배치의 미정산 후보 조회(created_at 범위)용 인덱스
CREATE INDEX idx_payout_candidate_items_created_at ON payout_candidate_items (created_at);
