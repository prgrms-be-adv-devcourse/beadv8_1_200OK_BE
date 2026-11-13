-- 지갑 잔액과 원장이 서로 맞는지 검사한다. 어긋난 줄만 돌려주며, 0줄이면 모두 맞다.
-- 시험 정리 단계와 로컬 기준점 측정에서 같은 파일을 쓴다. 로컬에서는 DB 도구에서 그대로 실행한다.
-- 잔액과 원장을 같은 시점의 값으로 비교하려고 모든 항목을 한 문장으로 묶는다.
--
-- 항목
--   UNKNOWN_LEDGER_TYPE: 증감 방향을 정하지 않은 원장 유형. 새 유형을 만들면 아래 CASE에 함께 넣는다.
--   LEDGER_CHAIN: 원장 번호 순으로 "직전 balance_after + 이번 증감 = 이번 balance_after"가 아니다.
--                 같은 지갑의 원장은 지갑 잠금을 쥔 채로만 들어가므로 원장 번호 순서가 곧 처리 순서다.
--   LAST_LEDGER_BALANCE: 마지막 원장의 balance_after가 지갑 잔액과 다르다. 원장이 없으면 잔액이 0원이어야 한다.
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
ORDER BY wallet_type, wallet_id, ledger_id;
