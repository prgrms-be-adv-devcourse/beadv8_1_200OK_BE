-- 미정산 조정 후보 조회(#291 findUnsettledAdjustments)용 인덱스: WHERE adjustment_id > 0
-- 조정은 관리자가 손으로 넣어 원래 후보보다 훨씬 적음. 인덱스가 없으면 배치마다 정산후보 표 전체를 읽음 (payout/CLAUDE.md 규모 전제)
CREATE INDEX idx_payout_candidate_items_adjustment_id ON payout_candidate_items (adjustment_id);
