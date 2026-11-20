CREATE TABLE member(
    id BIGINT NOT NULL AUTO_INCREMENT,
    login_id VARCHAR(20) NOT NULL,
    password VARCHAR(255) NOT NULL COMMENT '비밀번호 해시 (DelegatingPasswordEncoder 형식, 원문은 저장하지 않음)',
    email VARCHAR(255) NOT NULL,
    name VARCHAR(50) NOT NULL,
    nickname VARCHAR(30) NOT NULL,
    role VARCHAR(20) NOT NULL COMMENT '회원 권한 (BUYER, SELLER, ADMIN)',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '회원 상태 (ACTIVE, SUSPENDED, WITHDRAWN)',
    withdrawn_at DATETIME(6) NULL,
    terms_agreed_at DATETIME(6) NOT NULL,
    privacy_agreed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_member_login_id UNIQUE (login_id),
    CONSTRAINT uk_member_email UNIQUE (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
