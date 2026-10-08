CREATE TABLE payout_candidate_items
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    event_type     VARCHAR(50) NOT NULL,
    rel_id         BIGINT      NOT NULL,
    payer_id       BIGINT      NOT NULL,
    payee_id       BIGINT      NOT NULL,
    amount         BIGINT      NOT NULL,
    create_date    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payout_candidate_items_rel_id_event_type UNIQUE (rel_id, event_type)
);