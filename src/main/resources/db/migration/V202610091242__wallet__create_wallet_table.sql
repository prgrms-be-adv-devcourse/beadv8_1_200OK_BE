CREATE TABLE wallet (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id   BIGINT       NOT NULL,
    type        VARCHAR(20)  NOT NULL,
    balance     BIGINT       NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uk_wallet_member_type UNIQUE (member_id, type),
    CONSTRAINT ck_wallet_balance CHECK (balance >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
