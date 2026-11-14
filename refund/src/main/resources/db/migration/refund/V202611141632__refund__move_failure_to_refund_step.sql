-- 환불이 멈춘 단계와 이유를 refund의 두 칸(failed_step, failure_code)에서 단계 진행 표(refund_step)로 옮기고 두 칸을 지운다(#574 결정 3).
-- #574 전에 승인된 처리 중 환불은 옛 칸에만 실패가 남아 있다. 옮기지 않고 칸만 지우면 어디서 멈췄는지가 사라진다.
-- 특히 재고(PRODUCT) 실패를 PRODUCT 단계 줄로 옮겨야 "지갑 완료가 이미 반영됨 → 지갑 실패로 덮어쓰지 않음" 보호가 이어진다
-- (지갑 결과 처리는 PRODUCT 단계 줄이 있는지로 이를 판단한다).
-- 상태는 멈춤(STOPPED)으로 둔다. 옛 칸은 시도 횟수·다시 볼 시각을 몰라 자동으로 다시 시도할 근거가 없으니 사람이 보게 한다.
-- 같은 (환불, 단계) 줄이 이미 있으면(#574 뒤에 그 단계가 만들어진 경우) 그 줄이 더 최신이라 옮기지 않는다. UNIQUE 위반도 함께 피한다
INSERT INTO refund_step (refund_id, step, status, attempt_count, next_check_at, last_reason, created_at, updated_at)
SELECT r.id, r.failed_step, 'STOPPED', 1, NULL, r.failure_code, NOW(6), NOW(6)
FROM refund r
WHERE r.status = 'PROCESSING'
  AND r.failed_step IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM refund_step s
      WHERE s.refund_id = r.id
        AND s.step = r.failed_step
  );

ALTER TABLE refund
    DROP COLUMN failed_step,
    DROP COLUMN failure_code;
