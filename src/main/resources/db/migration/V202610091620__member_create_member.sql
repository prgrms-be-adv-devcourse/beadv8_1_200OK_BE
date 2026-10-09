CREATE TABLE member
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    login_id            VARCHAR(20)  NOT NULL,
    password            VARCHAR(255) NOT NULL,
    email               VARCHAR(255) NOT NULL,
    name                VARCHAR(50)  NOT NULL,
    nickname            VARCHAR(30)  NOT NULL,
    role                VARCHAR(20)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    withdrawn_at        DATETIME(6)  NULL,
    terms_agreed_at     DATETIME(6)  NOT NULL,
    privacy_agreed_at   DATETIME(6)  NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_member_login_id UNIQUE (login_id),
    CONSTRAINT uk_member_email UNIQUE (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;