-- 정산 목록 조회(#113)용 인덱스. 정렬은 created_at DESC, id DESC 고정

-- 판매자 목록: WHERE payee_id = ? [AND created_at 기간] ORDER BY created_at DESC, id DESC
-- InnoDB 보조 인덱스는 끝에 PK(id)를 함께 저장하므로 (payee_id, created_at)만으로 정렬까지 인덱스에서 처리됨
CREATE INDEX idx_payout_payee_id_created_at ON payout (payee_id, created_at);

-- 관리자 전체 목록: [WHERE created_at 기간] ORDER BY created_at DESC, id DESC
CREATE INDEX idx_payout_created_at ON payout (created_at);
