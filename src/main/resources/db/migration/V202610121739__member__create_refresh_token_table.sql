CREATE TABLE refresh_token (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL COMMENT '회원 ID (auth 모듈 분리 예정으로 외래키 없음)',
    token_hash VARCHAR(64) NOT NULL COMMENT '토큰 원문의 SHA-256 해시 (16진수 소문자 64자). 원문은 저장하지 않음',
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_token_hash UNIQUE (token_hash),
    INDEX idx_refresh_token_member_id (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;