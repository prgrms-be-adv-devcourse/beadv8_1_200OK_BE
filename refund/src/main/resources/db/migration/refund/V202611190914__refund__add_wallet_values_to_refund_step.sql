-- 지갑 결과가 환불과 달라(RESULT_MISMATCH) 지갑 줄을 처음 멈출 때, 지갑이 보낸 값을 남기는 칸(#761).
-- 돈을 자동으로 되돌리지 않고 관리자가 이 값과 환불 값을 나란히 보고 지갑과 맞춘다. 전에는 값이 WARN 로그에만 있었다.
-- 불일치가 없는 것이 정상이고 지갑이 아닌 줄(주문·재고·되돌리기)은 채울 값이 없어 모두 NULL을 허용한다.
-- 이미 멈춘 줄에 뒤늦게 온 결과는 처음 기록을 지키려고 채우지 않는다(로그에만 남김). 그래서 NULL이어도 불일치였을 수 있다.
-- CHECK를 나눈 이유: 한 제약으로 묶으면 이름 규칙대로 지은 이름이 MySQL 한도 64자를 넘는다.
-- 앞의 두 제약을 합치면 결과·주문·구매자·금액 네 칸이 함께 차거나 함께 빈다. 실패 이유는 실패 메시지일 때만, 그리고 반드시 들어간다.
-- 앱(WalletReport)도 같은 규칙으로 만들 때 막는다. DB에 둔 이유는 손으로 넣은 SQL처럼 앱을 거치지 않는 행도 막으려는 것이다
ALTER TABLE refund_step
    ADD COLUMN wallet_result VARCHAR(30) NULL
        COMMENT '지갑 결과 종류 (COMPLETED, FAILED). 결과 불일치로 지갑 줄을 처음 멈출 때만 채우고 그 밖에는 NULL' AFTER last_reason,
    ADD COLUMN wallet_order_id BIGINT NULL
        COMMENT '지갑이 보낸 주문 모듈 주문 ID. wallet_result와 함께 채움' AFTER wallet_result,
    ADD COLUMN wallet_buyer_id BIGINT NULL
        COMMENT '지갑이 보낸 회원 모듈 구매자 ID. wallet_result와 함께 채움' AFTER wallet_order_id,
    ADD COLUMN wallet_amount BIGINT NULL
        COMMENT '지갑이 보낸 금액 (원). 완료면 돌려준 금액, 실패면 돌려주려던 금액. wallet_result와 함께 채움' AFTER wallet_buyer_id,
    ADD COLUMN wallet_reason VARCHAR(100) NULL
        COMMENT '지갑이 보낸 실패 이유. wallet_result가 FAILED일 때만 채움' AFTER wallet_amount,
    ADD CONSTRAINT ck_refund_step_wallet_order_id_wallet_buyer_id_wallet_amount CHECK (
        (wallet_order_id IS NULL AND wallet_buyer_id IS NULL AND wallet_amount IS NULL)
        OR (wallet_order_id IS NOT NULL AND wallet_buyer_id IS NOT NULL AND wallet_amount IS NOT NULL)),
    ADD CONSTRAINT ck_refund_step_wallet_result_wallet_amount CHECK (
        (wallet_result IS NULL AND wallet_amount IS NULL)
        OR (wallet_result IS NOT NULL AND wallet_amount IS NOT NULL)),
    -- 결과 종류는 COMPLETED·FAILED만(대소문자까지 같게) 받는다. 다른 값이 들어가면 앱이 그 행을 읽지 못해 그 환불의 지갑 결과 처리가 막힌다.
    -- 실패면 이유가 꼭 있고 완료면 없다. 각 갈래에 IS NOT NULL을 둔 이유: NULL과 비교하면 결과가 "모름"이 되는데,
    -- CHECK는 "거짓"일 때만 거절해 결과 없이 이유만 있는 행이 통과한다. TRIM은 띄어쓰기만 지워 탭·줄바꿈만 든 이유는 통과한다(앱은 막음).
    -- 손으로 이유를 넣을 때는 글자가 든 값을 넣는다
    ADD CONSTRAINT ck_refund_step_wallet_reason_wallet_result CHECK (
        (wallet_result IS NULL AND wallet_reason IS NULL)
        OR (wallet_result IS NOT NULL AND wallet_result COLLATE utf8mb4_bin = 'COMPLETED' AND wallet_reason IS NULL)
        OR (wallet_result IS NOT NULL AND wallet_result COLLATE utf8mb4_bin = 'FAILED'
            AND wallet_reason IS NOT NULL AND TRIM(wallet_reason) <> '')),
    ADD CONSTRAINT ck_refund_step_wallet_amount CHECK (
        wallet_amount IS NULL OR wallet_amount > 0);
