CREATE TABLE media (
    id BIGINT NOT NULL AUTO_INCREMENT,
    purpose VARCHAR(30) NOT NULL,
    object_key VARCHAR(255) NOT NULL COMMENT 'S3에 저장된 파일 경로',
    content_type VARCHAR(40) NOT NULL COMMENT '저장된 파일의 MIME 타입',
    file_size BIGINT NOT NULL COMMENT '파일 크기 (bytes)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE (object_key)
);