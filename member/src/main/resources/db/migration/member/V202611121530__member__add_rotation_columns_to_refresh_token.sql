ALTER TABLE refresh_token
    ADD COLUMN rotated_at DATETIME(6) NULL COMMENT '마지막 재발급(회전) 일시. 한 번도 재발급하지 않았으면 NULL' AFTER expires_at;
