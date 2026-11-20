-- 막힌 주문 집계 표에 초과 청구 근거("막힌 순간" 값) 칸과 예비(PENDING) 상태를 더함 (#570)
-- 근거는 금액 검사(validate)가 이미 계산하는 값을 그 자리에서 저장함. Job 끝에 다시 계산하면 그사이 들어온 구매확정 때문에
--   실패 상세 글과 값이 달라질 수 있어서임(합계에 스냅샷 밖 대기 중 후보도 들어감)
-- 금액 검사 때는 그 주문이 막힘인지 아직 몰라 PENDING(예비) 줄로 넣고, Job 끝 갱신이 막힘이면 LOCKED·STOPPED로 바꾸고 아니면 지움
-- 근거 칸은 초과 청구가 원인인 줄에만 채우고 나머지는 NULL (근거를 계산할 수 없는 조정만 있는 주문도 NULL)
-- 목록 칸(주문상품·받은 금액)은 쉼표로 이은 글. 주문상품 수만큼 길어질 수 있어 TEXT로 둠 (길이를 넘겨 금액 검사가 롤백되면 매 배치 같은 이유로 실패함)
-- 아직 운영 데이터가 없어 기존 줄 채우기는 하지 않음 (기존 줄은 근거 NULL)
ALTER TABLE payout_blocked_order
    MODIFY COLUMN status VARCHAR(20) NOT NULL COMMENT '상태 (PayoutBlockedStatus): PENDING 예비(금액 검사가 넣음, 목록에 안 나옴), LOCKED 잠김, STOPPED 멈춤, RESOLVED 해결됨(다음 실행 끝에 정리)',
    ADD COLUMN evidence_deducted_amount BIGINT NULL COMMENT '초과 청구 근거: 결제(지갑 차감) 금액 (원)',
    ADD COLUMN evidence_candidate_total BIGINT NULL COMMENT '초과 청구 근거: 정산후보 합계 (원, 조정 제외)',
    ADD COLUMN evidence_overflow_amount BIGINT NULL COMMENT '초과 청구 근거: 넘친 금액 = 후보 합계 - 결제 금액 (원)',
    ADD COLUMN evidence_first_overflow_item VARCHAR(30) NULL COMMENT '초과 청구 근거: 넘게 만든 주문상품 ID, 배송비면 "배송비", 못 찾으면 "판별 불가"',
    ADD COLUMN evidence_settled_amount BIGINT NULL COMMENT '초과 청구 근거: 이미 지급한 금액 (원)',
    ADD COLUMN evidence_settled_items TEXT NULL COMMENT '초과 청구 근거: 이미 지급한 주문상품 (쉼표로 이음, 없으면 빈 글)',
    ADD COLUMN evidence_alone_over_items TEXT NULL COMMENT '초과 청구 근거: 혼자 결제 금액을 넘는 주문상품 (쉼표로 이음, 없으면 빈 글)',
    ADD COLUMN evidence_seller_count INT NULL COMMENT '초과 청구 근거: 그 주문 정산후보의 판매자 수',
    ADD COLUMN evidence_conflict_amounts TEXT NULL COMMENT '초과 청구 근거: 지갑 금액 충돌로 받은 금액 (원, 쉼표로 이음, 없으면 빈 글)';
