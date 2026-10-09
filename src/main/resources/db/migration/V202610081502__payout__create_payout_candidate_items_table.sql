CREATE TABLE payout_candidate_items
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    event_type     VARCHAR(50) NOT NULL,
    order_item_id  BIGINT      NOT NULL,
    seller_id      BIGINT      NOT NULL,
    amount         BIGINT      NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payout_candidate_items_order_item_id_event_type UNIQUE (order_item_id, event_type)
);

