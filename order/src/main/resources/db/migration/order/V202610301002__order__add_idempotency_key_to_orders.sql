-- 주문 생성 멱등키: 같은 구매자가 같은 키로 다시 요청하면 새로 만들지 않고 기존 주문을 돌려준다
-- 기존 주문에는 키가 없어 NULL을 허용한다 (MySQL 유니크 제약은 NULL끼리 중복을 허용)
ALTER TABLE orders
    ADD COLUMN idempotency_key VARCHAR(64) NULL COMMENT '주문 생성 요청의 Idempotency-Key 헤더 값' AFTER buyer_id,
    ADD CONSTRAINT uk_orders_buyer_id_idempotency_key UNIQUE (buyer_id, idempotency_key);
