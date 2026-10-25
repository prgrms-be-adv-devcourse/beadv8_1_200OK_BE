-- 지갑 충전에 필요한 약관. 개정 시 기존 행은 두고 새 버전을 추가하며, 유형별 최대 버전이 현재 버전이다.
CREATE TABLE wallet_terms (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    type        VARCHAR(40)   NOT NULL,
    version     INT           NOT NULL,
    title       VARCHAR(100)  NOT NULL,
    content     TEXT          NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    CONSTRAINT uk_wallet_terms_type_version UNIQUE (type, version)
);

-- 회원의 약관 동의 기록. 약관 ID가 유형과 버전을 함께 가리키며, 같은 버전에는 한 번만 동의가 저장된다.
CREATE TABLE wallet_terms_agreement (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id   BIGINT       NOT NULL,
    terms_id    BIGINT       NOT NULL,
    agreed_at   DATETIME(6)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT fk_wallet_terms_agreement_terms FOREIGN KEY (terms_id) REFERENCES wallet_terms (id),
    CONSTRAINT uk_wallet_terms_agreement_member_terms UNIQUE (member_id, terms_id)
);

-- 필수 약관 3종 첫 버전 (본문은 실제 약관 확정 시 새 버전으로 추가)
INSERT INTO wallet_terms (type, version, title, content, created_at, updated_at) VALUES
    ('ELECTRONIC_FINANCIAL_TRANSACTION', 1, '전자금융거래 이용약관', '전자금융거래 이용약관 본문', NOW(6), NOW(6)),
    ('PREPAID_PAYMENT_INSTRUMENT',       1, '선불전자지급수단 이용약관', '선불전자지급수단 이용약관 본문', NOW(6), NOW(6)),
    ('PERSONAL_INFORMATION_COLLECTION',  1, '개인정보 수집 및 이용 동의', '개인정보 수집 및 이용 동의 본문', NOW(6), NOW(6));
