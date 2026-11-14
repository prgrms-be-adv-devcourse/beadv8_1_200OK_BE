-- 막힌 주문 집계 표 (#582): "지금 정산이 막힌 주문"(잠김·멈춤)을 원본에서 계산해 적어 둔 조회용 사본(읽기 모델)
-- 원본(payout_failure·payout_failure_resolution)은 지금처럼 고치지 않고, 이 세 표만 예외로 수정·삭제를 허용함
--   사본이라 틀리면 원본에서 다시 계산해 바로잡을 수 있음 (범위 맞추기 #583)
-- 스냅샷은 이 표가 아니라 원본으로 판정함 (표가 낡아도 돈이 잘못 나가지 않게). 이 표는 관리자 목록(#569)·근거(#570)용
-- 정산 Job이 끝날 때 바뀐 주문만 다시 판정해 넣기·고치기·지우기, 해결 API는 그 주문 줄을 RESOLVED로 바꿈

-- 막힌 주문 1개 = 1줄
CREATE TABLE payout_blocked_order (
    order_id BIGINT NOT NULL COMMENT '주문 ID (order 모듈)',
    status VARCHAR(20) NOT NULL COMMENT '상태 (PayoutBlockedStatus): LOCKED 잠김, STOPPED 멈춤, RESOLVED 해결됨(다음 실행 끝에 정리)',
    failed_run_count INT NOT NULL COMMENT '성공한 실행에서 해결 안 된 실패가 남은 실행 수 (#549 멈춤 판정 값). 잠김은 이 값과 상관없음',
    last_failed_at DATETIME(6) NOT NULL COMMENT '해결 안 된 실패 기록 중 가장 늦은 기록 시각',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 관리자 목록(#569): 상태로 거르고 마지막 실패 시각 최신순
CREATE INDEX idx_payout_blocked_order_status_last_failed_at ON payout_blocked_order (status, last_failed_at);

-- 막힌 주문의 원인 기록 1개 = 1줄 (해결 안 된 실패 기록 중 함께 보류를 빼고, 주문상품·종류·사유·상세가 같은 기록은 1번만)
CREATE TABLE payout_blocked_order_cause (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    order_item_id BIGINT NULL COMMENT '주문상품 ID (order 모듈). 배송비 후보는 NULL',
    event_type VARCHAR(50) NOT NULL COMMENT '실패한 정산후보 종류 (PayoutEventType)',
    reason VARCHAR(50) NOT NULL COMMENT '실패 사유 (PayoutFailureReason)',
    detail VARCHAR(255) NOT NULL COMMENT '실패 상세',
    seller_id BIGINT NOT NULL COMMENT '판매자 회원 ID (member 모듈)',
    failed_at DATETIME(6) NOT NULL COMMENT '같은 원인 기록 중 가장 늦은 실패 시각',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_payout_blocked_order_cause_order_id FOREIGN KEY (order_id) REFERENCES payout_blocked_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 갱신 상태 1줄: 어디까지의 실패 기록을 표에 반영했는지
-- 다음 갱신은 이 번호 뒤의 실패 기록만 읽음 (운영 시작 이후 실패 기록 전체를 매번 읽지 않으려고, payout/CLAUDE.md 설계 전제)
CREATE TABLE payout_blocked_order_refresh (
    id BIGINT NOT NULL COMMENT '늘 1 (한 줄만 둠)',
    last_failure_id BIGINT NOT NULL COMMENT '표에 반영한 마지막 실패 기록 ID (payout_failure.id)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 처음엔 아무것도 반영하지 않음: 첫 정산 Job이 끝날 때 그때까지의 실패 기록을 따라잡음 (운영 데이터 없음)
INSERT INTO payout_blocked_order_refresh (id, last_failure_id, created_at, updated_at)
VALUES (1, 0, NOW(6), NOW(6));
