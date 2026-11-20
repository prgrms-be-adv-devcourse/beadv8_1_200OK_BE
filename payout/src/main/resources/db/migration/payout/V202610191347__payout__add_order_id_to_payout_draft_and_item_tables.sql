-- 임시 정산 항목·정산 항목에 주문 ID 추가 (#73)
-- 정산후보의 order_id를 그대로 복사하고, 배송비 행은 order_item_id가 NULL (정산후보와 같은 규칙)
-- 개발 DB 초기화 전제 (기존 데이터 이관 없음). 기존 행이 있으면 order_id가 0으로 채워짐

-- 판매자 몫: 판매 대금, 배송비 (배송비 행은 order_item_id NULL)
ALTER TABLE payout_seller_draft_item
    ADD COLUMN order_id BIGINT NOT NULL COMMENT '주문 ID (order 모듈)' AFTER event_type,
    MODIFY COLUMN order_item_id BIGINT NULL COMMENT '주문상품 ID (order 모듈). 배송비 행은 NULL';

-- 시스템 몫: 판매 수수료만 들어오므로 order_item_id는 NOT NULL 유지
ALTER TABLE payout_system_draft_item
    ADD COLUMN order_id BIGINT NOT NULL COMMENT '주문 ID (order 모듈)' AFTER event_type;

-- 정산 항목: 판매자 몫(대금, 배송비)과 시스템 몫(수수료)이 함께 들어옴 (배송비 행은 order_item_id NULL)
ALTER TABLE payout_item
    ADD COLUMN order_id BIGINT NOT NULL COMMENT '주문 ID (order 모듈)' AFTER event_type,
    MODIFY COLUMN order_item_id BIGINT NULL COMMENT '주문상품 ID (order 모듈). 배송비 행은 NULL';
