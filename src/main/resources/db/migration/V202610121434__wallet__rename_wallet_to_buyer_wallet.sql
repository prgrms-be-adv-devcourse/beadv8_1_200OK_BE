ALTER TABLE wallet
    RENAME TO buyer_wallet,
    DROP INDEX uk_wallet_member_type,
    DROP CHECK ck_wallet_balance,
    DROP COLUMN type,
    ADD CONSTRAINT uk_buyer_wallet_member_id UNIQUE (member_id),
    ADD CONSTRAINT ck_buyer_wallet_balance CHECK (balance >= 0);
