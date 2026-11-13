-- 지갑 잔액과 원장이 맞는지 검사한다. 어긋난 줄만 돌려주므로 0줄이면 모두 맞다.
-- 시험 정리 단계와 로컬 기준점 측정에 같이 쓰고, 잔액과 원장을 같은 시점으로 비교하려고 한 문장으로 쓴다.
-- 새 원장 유형을 만들면 아래 CASE에 증감 방향을 넣는다. 넣지 않으면 UNKNOWN_LEDGER_TYPE으로 잡힌다.
-- 원장은 지갑 잠금을 쥔 채로만 들어가므로 원장 번호 순서를 처리 순서로 본다.
WITH buyer_ledger AS (
    SELECT l.id,
           l.buyer_wallet_id AS wallet_id,
           l.type,
           l.balance_after,
           CASE l.type
               WHEN 'CHARGE' THEN l.amount
               WHEN 'REFUND' THEN l.amount
               WHEN 'PAYMENT' THEN -l.amount
           END AS delta,
           LAG(l.balance_after, 1, 0) OVER (PARTITION BY l.buyer_wallet_id ORDER BY l.id) AS previous_balance_after,
           ROW_NUMBER() OVER (PARTITION BY l.buyer_wallet_id ORDER BY l.id DESC) AS reverse_order
    FROM buyer_wallet_ledger l
),
seller_ledger AS (
    SELECT l.id,
           l.seller_wallet_id AS wallet_id,
           l.type,
           l.balance_after,
           CASE l.type
               WHEN 'PAYOUT' THEN l.amount
           END AS delta,
           LAG(l.balance_after, 1, 0) OVER (PARTITION BY l.seller_wallet_id ORDER BY l.id) AS previous_balance_after,
           ROW_NUMBER() OVER (PARTITION BY l.seller_wallet_id ORDER BY l.id DESC) AS reverse_order
    FROM seller_wallet_ledger l
)
SELECT 'BUYER' AS wallet_type, wallet_id, 'UNKNOWN_LEDGER_TYPE' AS check_item, id AS ledger_id,
       NULL AS expected, CAST(type AS CHAR) AS actual
FROM buyer_ledger
WHERE delta IS NULL
UNION ALL
SELECT 'BUYER', wallet_id, 'LEDGER_CHAIN', id,
       CAST(previous_balance_after + delta AS CHAR), CAST(balance_after AS CHAR)
FROM buyer_ledger
WHERE delta IS NOT NULL
  AND previous_balance_after + delta <> balance_after
UNION ALL
SELECT 'BUYER', w.id, 'LAST_LEDGER_BALANCE', l.id,
       CAST(w.balance AS CHAR), CAST(COALESCE(l.balance_after, 0) AS CHAR)
FROM buyer_wallet w
LEFT JOIN buyer_ledger l ON l.wallet_id = w.id AND l.reverse_order = 1
WHERE COALESCE(l.balance_after, 0) <> w.balance
UNION ALL
SELECT 'SELLER', wallet_id, 'UNKNOWN_LEDGER_TYPE', id,
       NULL, CAST(type AS CHAR)
FROM seller_ledger
WHERE delta IS NULL
UNION ALL
SELECT 'SELLER', wallet_id, 'LEDGER_CHAIN', id,
       CAST(previous_balance_after + delta AS CHAR), CAST(balance_after AS CHAR)
FROM seller_ledger
WHERE delta IS NOT NULL
  AND previous_balance_after + delta <> balance_after
UNION ALL
SELECT 'SELLER', w.id, 'LAST_LEDGER_BALANCE', l.id,
       CAST(w.balance AS CHAR), CAST(COALESCE(l.balance_after, 0) AS CHAR)
FROM seller_wallet w
LEFT JOIN seller_ledger l ON l.wallet_id = w.id AND l.reverse_order = 1
WHERE COALESCE(l.balance_after, 0) <> w.balance
UNION ALL
-- 충전은 충전 건 쪽에서만 본다. 시험들이 chargeByPayment를 직접 불러 충전 건 없는 원장을 만들기 때문이다
SELECT 'BUYER', w.id, 'CHARGE_WITHOUT_LEDGER', NULL,
       CONCAT('CHARGE:', c.payment_key), NULL
FROM wallet_charge c
LEFT JOIN buyer_wallet w ON w.member_id = c.member_id
WHERE c.status = 'COMPLETED'
  AND NOT EXISTS (SELECT 1 FROM buyer_wallet_ledger l WHERE l.reference_key = CONCAT('CHARGE:', c.payment_key))
UNION ALL
SELECT 'BUYER', w.id, 'CHARGE_LEDGER_MISMATCH', l.id,
       CONCAT('member=', c.member_id, ', amount=', c.amount),
       CONCAT('member=', w.member_id, ', amount=', l.amount)
FROM wallet_charge c
JOIN buyer_wallet_ledger l ON l.reference_key = CONCAT('CHARGE:', c.payment_key)
JOIN buyer_wallet w ON w.id = l.buyer_wallet_id
WHERE c.status = 'COMPLETED'
  AND (w.member_id <> c.member_id OR l.amount <> c.amount)
UNION ALL
-- 결제 키가 없는 실패 건은 토스 승인 전 실패라 원장이 생길 수 없어, CONCAT이 NULL이 되며 빠진다
SELECT 'BUYER', w.id, 'FAILED_CHARGE_WITH_LEDGER', l.id,
       NULL, l.reference_key
FROM wallet_charge c
JOIN buyer_wallet_ledger l ON l.reference_key = CONCAT('CHARGE:', c.payment_key)
JOIN buyer_wallet w ON w.id = l.buyer_wallet_id
WHERE c.status = 'FAILED'
UNION ALL
SELECT 'BUYER', w.id, 'PAID_WITHOUT_LEDGER', NULL,
       CONCAT('PAYMENT:', p.order_id), NULL
FROM wallet_order_payment p
LEFT JOIN buyer_wallet w ON w.member_id = p.buyer_id
WHERE p.status = 'PAID'
  AND NOT EXISTS (SELECT 1 FROM buyer_wallet_ledger l WHERE l.reference_key = CONCAT('PAYMENT:', p.order_id))
UNION ALL
SELECT 'BUYER', w.id, 'PAYMENT_LEDGER_MISMATCH', l.id,
       CONCAT('member=', p.buyer_id, ', amount=', p.amount),
       CONCAT('member=', w.member_id, ', amount=', l.amount)
FROM wallet_order_payment p
JOIN buyer_wallet_ledger l ON l.reference_key = CONCAT('PAYMENT:', p.order_id)
JOIN buyer_wallet w ON w.id = l.buyer_wallet_id
WHERE p.status = 'PAID'
  AND (w.member_id <> p.buyer_id OR l.amount <> p.amount)
UNION ALL
SELECT 'BUYER', w.id, 'FAILED_PAYMENT_WITH_LEDGER', l.id,
       NULL, l.reference_key
FROM wallet_order_payment p
JOIN buyer_wallet_ledger l ON l.reference_key = CONCAT('PAYMENT:', p.order_id)
JOIN buyer_wallet w ON w.id = l.buyer_wallet_id
WHERE p.status = 'FAILED'
UNION ALL
SELECT 'BUYER', l.buyer_wallet_id, 'PAYMENT_LEDGER_WITHOUT_RECORD', l.id,
       NULL, l.reference_key
FROM buyer_wallet_ledger l
WHERE l.type = 'PAYMENT'
  AND NOT EXISTS (SELECT 1 FROM wallet_order_payment p WHERE CONCAT('PAYMENT:', p.order_id) = l.reference_key)
ORDER BY wallet_type, wallet_id, ledger_id;
