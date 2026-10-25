CREATE TABLE seller_wallet (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id   BIGINT       NOT NULL,
    balance     BIGINT       NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uk_seller_wallet_member_id UNIQUE (member_id),
    CONSTRAINT ck_seller_wallet_balance CHECK (balance >= 0)
);
