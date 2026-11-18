-- draftStep이 판매자를 정산후보 표 전체가 아니라 이번 스냅샷 목록에서 바로 고르게 판매자 번호를 복사해 둠 (#304)
ALTER TABLE payout_snapshot_item
    ADD COLUMN seller_id BIGINT NULL COMMENT '판매자 회원 ID (member 모듈). 스냅샷 시점 정산후보에서 복사' AFTER candidate_id;

-- 이미 있는 줄은 정산후보에서 채움: 정산후보는 저장 뒤 바뀌지 않아(@Immutable) 복사한 값과 어긋나지 않음
UPDATE payout_snapshot_item si
    JOIN payout_candidate_items c ON c.id = si.candidate_id
SET si.seller_id = c.seller_id;

ALTER TABLE payout_snapshot_item
    MODIFY COLUMN seller_id BIGINT NOT NULL COMMENT '판매자 회원 ID (member 모듈). 스냅샷 시점 정산후보에서 복사';

-- draftStep: 이번 스냅샷에서 "지난번 마지막 판매자 다음" 100명을 번호 순으로 고르고, 그 판매자들의 줄만 찾음
CREATE INDEX idx_payout_snapshot_item_snapshot_id_seller_id ON payout_snapshot_item (snapshot_id, seller_id);
