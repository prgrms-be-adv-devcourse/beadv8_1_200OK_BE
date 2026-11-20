-- 토큰 버전 (#561): 비밀번호를 바꾸면 버전을 올려, 그 전에 발급된 Access Token과 Refresh Token을 만료 전이라도 거부한다.
-- 기존 회원과 ver 클레임이 없는 옛 토큰이 모두 0으로 맞도록 기본값을 0으로 둔다.
-- 변경 시각은 Redis가 비거나 쓰기에 실패했을 때 최근 변경분만 골라 다시 채우는 데 쓴다. 한 번도 바꾸지 않았으면 NULL이다
ALTER TABLE member
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0 COMMENT '토큰 버전. 이 값보다 낮은 버전으로 발급된 토큰은 거부한다',
    ADD COLUMN token_version_changed_at DATETIME(6) NULL COMMENT '토큰 버전을 마지막으로 올린 시각',
    ADD INDEX idx_member_token_version_changed_at (token_version_changed_at);
