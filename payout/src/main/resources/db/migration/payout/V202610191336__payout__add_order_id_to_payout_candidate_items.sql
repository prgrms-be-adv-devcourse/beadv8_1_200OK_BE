-- 정산후보: 주문 ID 추가, 배송비 후보를 (주문, 판매자)당 1건으로 제한 (#73)
-- 개발 DB 초기화 전제 (기존 데이터 이관 없음)
-- 기존 행이 있으면 order_id가 0으로 채워지고, 배송비 행(order_item_id 있음) 때문에 CHECK 추가가 실패함
ALTER TABLE payout_candidate_items
    ADD COLUMN order_id BIGINT NOT NULL COMMENT '주문 ID (order 모듈)' AFTER event_type,
    MODIFY COLUMN order_item_id BIGINT NULL COMMENT '주문상품 ID (order 모듈). 배송비 행은 NULL (배송비는 상품이 아니라 판매자 묶음 배송 1건의 금액)',
    -- 배송비 행일 때만 seller_id, 그 외 NULL. MySQL은 조건부 UK가 없어 생성 컬럼으로 "배송비 행에만 거는 UK"를 만듦
    ADD COLUMN ship_seller_id BIGINT AS (CASE WHEN event_type = 'SALE_SHIPPING_FEE' THEN seller_id END) STORED
        COMMENT '배송비 행의 판매자 ID (그 외 NULL, DB가 계산)';

ALTER TABLE payout_candidate_items
    -- 배송비 후보는 (주문, 판매자, 조정)당 1건. 대금·수수료 행은 ship_seller_id가 NULL이라 이 UK에서 빠짐
    -- order_id로 시작해서 order_id 조회 인덱스도 겸함 (별도 인덱스 없음)
    ADD CONSTRAINT uk_payout_candidate_items_order_id_ship_seller_id_adjustment_id
        UNIQUE (order_id, ship_seller_id, adjustment_id),
    -- 배송비만 order_item_id가 NULL, 대금·수수료는 반드시 있음
    ADD CONSTRAINT ck_payout_candidate_items_event_type_order_item_id
        CHECK ((event_type = 'SALE_SHIPPING_FEE') = (order_item_id IS NULL));
