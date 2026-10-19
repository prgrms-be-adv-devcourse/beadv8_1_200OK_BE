CREATE TABLE seller_application (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    store_name VARCHAR(50) NOT NULL COMMENT '상호명',
    representative_name VARCHAR(50) NOT NULL COMMENT '대표자명',
    business_number VARCHAR(12) NOT NULL COMMENT '사업자등록번호 (000-00-00000 형식으로 정규화)',
    mail_order_number VARCHAR(50) NOT NULL COMMENT '통신판매업 신고번호',
    status VARCHAR(20) NOT NULL COMMENT '신청 상태 (PENDING, IN_REVIEW, APPROVED, REJECTED)',
    applied_at DATETIME(6) NOT NULL COMMENT '신청 일시',
    review_started_at DATETIME(6) NULL COMMENT '심사 시작 일시',
    approved_at DATETIME(6) NULL COMMENT '승인 일시',
    rejected_at DATETIME(6) NULL COMMENT '거절 일시',
    reject_reason VARCHAR(500) NULL COMMENT '거절 사유. 거절된 신청에만 있다',
    reviewer_id BIGINT NULL COMMENT '승인·거절을 결정한 관리자 회원 ID. 결정 전에는 NULL',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '낙관적 락 버전. 관리자 두 명이 같은 신청을 동시에 처리하는 것을 막는다',
    -- MySQL은 조건부(부분) 유니크 인덱스가 없어, 거절된 신청만 NULL이 되는 생성 컬럼에 유니크를 건다.
    -- NULL은 유니크 비교에서 제외되므로 거절 이력은 여러 건 남고, 진행 중·승인 신청은 회원당·사업자등록번호당 하나만 남는다.
    active_member_id BIGINT GENERATED ALWAYS AS (IF(status = 'REJECTED', NULL, member_id)) STORED
    COMMENT '거절되지 않은 신청의 회원 ID (회원당 진행 중·승인 신청 1건 보장용, 엔티티에 매핑하지 않음)',
    active_business_number VARCHAR(12) GENERATED ALWAYS AS (IF(status = 'REJECTED', NULL, business_number)) STORED
    COMMENT '거절되지 않은 신청의 사업자등록번호 (사업자등록번호당 1건 보장용, 엔티티에 매핑하지 않음)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_seller_application_active_member_id UNIQUE (active_member_id),
    CONSTRAINT uk_seller_application_active_business_number UNIQUE (active_business_number),
    CONSTRAINT fk_seller_application_member_id FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_seller_application_reviewer_id FOREIGN KEY (reviewer_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_seller_application_business_number ON seller_application (business_number);
