-- 정산·지갑 대사 (#619, 상위 #308)
-- 정산이 매일 "이 정산들, 지갑에 입금됐나요?"를 Kafka로 묶어서 묻고, 지갑의 답으로 정산마다 판정함

-- 질문 묶음 1개 = 1줄 (쌓기만 함)
-- id가 지갑과 주고받는 묶음 번호(checkId). 번호가 겹치면 "가장 최근 질문의 답만 처리" 규칙이 깨져 DB 자동 번호를 씀
-- Kafka로 보내기에 실패한 묶음도 남음 (번호를 먼저 받아야 메시지를 만들 수 있어서). 그 번호를 가리키는 대사 줄은 없음
CREATE TABLE payout_deposit_check_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    item_count INT NOT NULL COMMENT '이 묶음에 담은 정산 수 (최대 500)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 대사 표: 확인할 정산 1건 = 1줄 (정산 확정 때 outbox 줄과 함께 넣음)
-- 정산 기록 표 중 예외로 수정을 허용함 (payout/CLAUDE.md): 돈의 원본이 아니라 "지갑 확인이 어디까지 갔는지"를 담는 진행 상태 표
CREATE TABLE payout_deposit_check (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payout_id BIGINT NOT NULL,
    payee_id BIGINT NOT NULL COMMENT '정산이 기대하는 판매자 회원 ID (member 모듈). 정산(payout)은 바뀌지 않아 복사해 둠',
    amount BIGINT NOT NULL COMMENT '정산이 기대하는 입금 금액(원), 1 이상. 정산(payout)은 바뀌지 않아 복사해 둠',
    status VARCHAR(20) NOT NULL COMMENT '대사 상태 (PayoutDepositCheckStatus): WAITING 확인 대기, MATCHED 일치, NEEDS_REVIEW 관리자 확인 필요',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '지갑에 물어본 횟수 (Kafka로 보내기에 성공한 질문만 셈)',
    not_found_count INT NOT NULL DEFAULT 0 COMMENT '지갑이 "없음"으로 답한 횟수',
    last_check_id BIGINT NULL COMMENT '가장 최근에 물은 질문 묶음 번호. 이 번호의 답만 처리함',
    last_check_answered BOOLEAN NOT NULL DEFAULT FALSE COMMENT '가장 최근 질문의 답을 처리했는지 (같은 답이 두 번 오면 두 번째를 버림)',
    last_checked_at DATETIME(6) NULL COMMENT '가장 최근에 물은 시각',
    mismatch_reason VARCHAR(30) NULL COMMENT '관리자 확인 필요 이유 (PayoutDepositMismatchReason). 그 밖의 상태는 NULL',
    wallet_payee_id BIGINT NULL COMMENT '지갑이 답한 판매자 회원 ID (member 모듈). "있음" 답을 받았을 때만',
    wallet_amount BIGINT NULL COMMENT '지갑이 답한 입금 금액(원). "있음" 답을 받았을 때만',
    wallet_deposited_at DATETIME(6) NULL COMMENT '지갑이 입금한 시각. "있음" 답을 받았을 때만',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '낙관적 잠금 버전. 매일 대사와 지갑 답이 같은 줄을 동시에 고쳐 한쪽 결과가 덮이지 않게 함',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 같은 정산이 두 줄 생기지 않게 함
    CONSTRAINT uk_payout_deposit_check_payout_id UNIQUE (payout_id),
    CONSTRAINT fk_payout_deposit_check_payout_id FOREIGN KEY (payout_id) REFERENCES payout (id),
    CONSTRAINT fk_payout_deposit_check_last_check_id FOREIGN KEY (last_check_id) REFERENCES payout_deposit_check_batch (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 매일 대사: 확인 대기 줄을 id 순으로 이어서 읽음 (운영 시작 이후 전체를 읽지 않고 대기 줄만)
CREATE INDEX idx_payout_deposit_check_status_id ON payout_deposit_check (status, id);
