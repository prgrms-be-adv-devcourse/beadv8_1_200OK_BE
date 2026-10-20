-- 정산후보 조회(#236)용 인덱스. 정렬은 created_at DESC, id DESC 고정

-- 판매자 조회·관리자 판매자 필터: WHERE seller_id = ? AND created_at 기간 ORDER BY created_at DESC, id DESC
-- InnoDB 보조 인덱스는 끝에 PK(id)를 함께 저장하므로 (seller_id, created_at)만으로 정렬까지 인덱스에서 처리됨
CREATE INDEX idx_payout_candidate_items_seller_id_created_at ON payout_candidate_items (seller_id, created_at);

-- 관리자 전체 조회: WHERE created_at 기간 ORDER BY created_at DESC, id DESC
-- 기존 idx_payout_candidate_items_created_at(V202610091950)을 그대로 씀
