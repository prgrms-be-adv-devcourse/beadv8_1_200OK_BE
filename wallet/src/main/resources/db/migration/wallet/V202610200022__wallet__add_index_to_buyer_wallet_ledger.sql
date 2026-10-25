-- 이용 내역 조회는 지갑별로 최신순으로 읽으므로, 지갑 ID와 생성 시각으로 인덱스를 둔다.
-- 기간 조건(created_at 범위)도 이 인덱스로 함께 좁힌다.
CREATE INDEX idx_buyer_wallet_ledger_buyer_wallet_id_created_at
    ON buyer_wallet_ledger (buyer_wallet_id, created_at);
