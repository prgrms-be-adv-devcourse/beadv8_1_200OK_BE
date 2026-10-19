-- 예치금 충전 건. 토스 결제창 호출 전에 대기 상태로 만들고, 승인 결과에 따라 완료 또는 실패로 바뀐다.
-- charge_no는 토스 orderId로 쓰이는 외부 노출용 번호이고, 내부 PK와 분리한다.
CREATE TABLE wallet_charge (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    charge_no        VARCHAR(40)   NOT NULL,
    member_id        BIGINT        NOT NULL,
    amount           BIGINT        NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    payment_key      VARCHAR(200)  NULL,
    failure_code     VARCHAR(100)  NULL,
    failure_message  VARCHAR(500)  NULL,
    completed_at     DATETIME(6)   NULL,
    failed_at        DATETIME(6)   NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    CONSTRAINT uk_wallet_charge_charge_no UNIQUE (charge_no),
    -- 같은 토스 결제가 두 충전 건에 연결되지 않게 한다 (NULL은 여러 건 허용)
    CONSTRAINT uk_wallet_charge_payment_key UNIQUE (payment_key),
    CONSTRAINT ck_wallet_charge_amount CHECK (amount > 0),
    -- 잔액 한도 확인 시 회원의 처리 중 충전 금액 합계를 조회한다
    INDEX idx_wallet_charge_member_status (member_id, status)
);
