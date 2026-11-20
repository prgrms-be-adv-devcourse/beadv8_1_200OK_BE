-- 해결 기록 전체 목록(#723 GET /api/v1/admin/payouts/resolution-requests)용 인덱스: 기간(created_at)으로 해결 요청 번호를 고름
-- 해결 요청 번호를 함께 둬서, 기간 안 요청 번호·가장 이른 시각을 표를 다시 읽지 않고 이 인덱스만으로 고름 (기본 키 id는 InnoDB 보조 인덱스에 들어 있음)
-- 관리자 ID는 넣지 않음: 컬럼 이름을 줄이지 않는 이름 규칙으로는 세 칸 이름이 MySQL 한도 64자를 넘음. 관리자로 거를 때는 기간 안 줄을 표에서 읽어 거름
-- 이 인덱스가 없으면 기간 조회마다 해결 기록 표 전체를 읽음 (payout/CLAUDE.md 규모 전제)
CREATE INDEX idx_payout_failure_resolution_created_at_resolution_request_id ON payout_failure_resolution (created_at, resolution_request_id);
