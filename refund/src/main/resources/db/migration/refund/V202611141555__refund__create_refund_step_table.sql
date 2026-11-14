-- 환불 승인 뒤 단계(주문·지갑·상품)의 진행 기록(#574). 환불 하나에 단계마다 한 행이고, 그 단계 차례가 되면 만든다.
-- 아직 오지 않은 단계는 행이 없다. 지우지 않고 상태로만 끝내므로 deleted_at을 두지 않는다.
-- 행을 만들거나 고칠 때는 같은 트랜잭션에서 refund 행을 먼저 잠근다(단계 행은 따로 잠그지 않는다, 결정 4)
CREATE TABLE refund_step (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_id BIGINT NOT NULL COMMENT '환불 ID (같은 모듈 refund 테이블)',
    step VARCHAR(30) NOT NULL COMMENT '단계 (ORDER, WALLET, PRODUCT)',
    status VARCHAR(30) NOT NULL COMMENT '단계 상태 (READY 할 차례, IN_PROGRESS 시도 중, AWAITING_RESULT 결과 대기, SUCCEEDED 성공, REFUSED 거절됨, STOPPED 멈춤). 끝 상태(SUCCEEDED, REFUSED, STOPPED)는 다시 바뀌지 않는다',
    attempt_count INT NOT NULL COMMENT '시도한 횟수 (처음 만들 때 1)',
    next_check_at DATETIME(6) NULL COMMENT '다시 볼 시각. 상태마다 뜻이 다르다: 할 차례(READY)=다시 시도 시각, 시도 중(IN_PROGRESS)=이 시각이 지나면 시도한 서버가 죽은 것으로 봄, 결과 대기(AWAITING_RESULT)=이 시각이 지나도 결과가 없으면 시간 초과, 끝 상태=NULL',
    last_reason VARCHAR(100) NULL COMMENT '마지막 실패·거절·멈춤 이유: 에러 코드 이름, 없으면 예외 클래스 이름. 실패한 적이 없으면 NULL',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_step_refund_id_step UNIQUE (refund_id, step),
    CONSTRAINT fk_refund_step_refund_id FOREIGN KEY (refund_id) REFERENCES refund (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
