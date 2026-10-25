-- 시스템(플랫폼) 몫 정산은 회원 수취인이 없으므로 payee_id = NULL, is_system = TRUE
ALTER TABLE payout
    MODIFY COLUMN payee_id BIGINT NULL,
    ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE AFTER payee_id,
    ADD CONSTRAINT chk_payout_payee_system
        CHECK ((is_system = TRUE AND payee_id IS NULL) OR (is_system = FALSE AND payee_id IS NOT NULL));
