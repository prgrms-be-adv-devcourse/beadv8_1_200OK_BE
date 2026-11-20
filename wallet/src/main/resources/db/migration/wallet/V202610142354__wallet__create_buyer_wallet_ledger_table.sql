CREATE TABLE buyer_wallet_ledger (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    buyer_wallet_id  BIGINT        NOT NULL,
    type             VARCHAR(20)   NOT NULL,
    amount           BIGINT        NOT NULL,
    balance_after    BIGINT        NOT NULL,
    reference_key    VARCHAR(255)  NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    CONSTRAINT fk_buyer_wallet_ledger_buyer_wallet FOREIGN KEY (buyer_wallet_id) REFERENCES buyer_wallet (id),
    CONSTRAINT uk_buyer_wallet_ledger_reference_key UNIQUE (reference_key),
    CONSTRAINT ck_buyer_wallet_ledger_amount CHECK (amount > 0),
    CONSTRAINT ck_buyer_wallet_ledger_balance_after CHECK (balance_after >= 0)
);
