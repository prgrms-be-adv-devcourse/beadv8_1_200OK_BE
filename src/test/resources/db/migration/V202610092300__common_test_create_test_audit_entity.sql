CREATE TABLE test_audit_entity
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(255) NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
);