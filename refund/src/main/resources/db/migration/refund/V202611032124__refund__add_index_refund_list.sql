-- 환불 목록 조회(#408)용 인덱스. 두 목록 모두 최신순(created_at, id 내림차순)으로 25건씩 읽는다
-- 구매자 목록: buyer_id로 거르고 created_at으로 정렬한다
CREATE INDEX idx_refund_buyer_id_created_at ON refund (buyer_id, created_at);
-- 판매자 목록: 처리할 요청(REQUESTED)만 보는 경우가 많아 status를 가운데 둔다.
-- 상태를 거르지 않으면 seller_id로 범위만 좁히고 정렬은 따로 한다(filesort). 판매자당 환불 건수가 적어 지금은 인덱스를 더 두지 않는다
CREATE INDEX idx_refund_seller_id_status_created_at ON refund (seller_id, status, created_at);
