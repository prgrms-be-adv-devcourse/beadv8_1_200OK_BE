-- 이용 내역에 상품 이름을 보여 주려고 주문이 결제 요청에 담아 보내는 요약을 결제 처리 기록에 남긴다.
-- 이 칸이 생기기 전 기록과 만료로 먼저 남긴 기록에는 요약이 없어 NULL을 허용한다
ALTER TABLE wallet_order_payment
    ADD COLUMN order_summary VARCHAR(120) NULL COMMENT '이용 내역에 보일 주문 요약 (예: 사과 외 3건, 주문 시점 이름)' AFTER amount;
