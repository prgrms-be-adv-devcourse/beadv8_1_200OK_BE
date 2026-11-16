-- 승인 뒤 다른 모듈 호출(지갑·주문·재고, #413·#414)이 실패했을 때 멈춘 단계와 이유를 남기는 칸(#412).
-- 실패가 없는 것이 정상이라 채울 값이 없어 NULL을 허용한다. 빈 값으로 채우면 "실패했는데 이유가 빈 것"과 구분되지 않는다
ALTER TABLE refund
    ADD COLUMN failed_step VARCHAR(30) NULL
        COMMENT '처리 중에 멈춘 단계 (WALLET, ORDER, PRODUCT). 실패가 없으면 NULL' AFTER decided_at,
    ADD COLUMN failure_code VARCHAR(100) NULL
        COMMENT '멈춘 이유: 에러 코드 이름, 없으면 예외 클래스 이름. 실패가 없으면 NULL' AFTER failed_step;
