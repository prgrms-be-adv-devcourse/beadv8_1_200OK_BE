-- 구매자 배송 목록은 구매자별로 최신순으로 읽으므로, 구매자 ID와 생성 시각으로 인덱스를 둔다 (#201)
CREATE INDEX idx_shipment_buyer_id_created_at
    ON shipment (buyer_id, created_at);
