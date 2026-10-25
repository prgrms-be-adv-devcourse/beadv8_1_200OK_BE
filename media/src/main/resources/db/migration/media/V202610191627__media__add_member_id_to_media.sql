ALTER TABLE media
    ADD COLUMN member_id BIGINT NULL COMMENT '업로드한 회원 모듈 ID (컬럼 추가 전 업로드는 NULL)' AFTER id;

CREATE INDEX idx_media_member_id_created_at ON media (member_id, created_at);
