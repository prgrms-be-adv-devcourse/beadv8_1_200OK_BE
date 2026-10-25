-- 판매자 출고 목록은 판매자별로 최신순으로 읽으므로, 판매자 ID와 생성 시각으로 인덱스를 둔다 (#202)
CREATE INDEX idx_shipment_seller_id_created_at
    ON shipment (seller_id, created_at);
