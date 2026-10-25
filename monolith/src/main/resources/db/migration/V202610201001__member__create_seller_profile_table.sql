CREATE TABLE seller_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    store_name VARCHAR(50) NOT NULL COMMENT '상호명 (신청 시 상호명과 별개로 수정할 수 있다)',
    representative_name VARCHAR(50) NOT NULL COMMENT '대표자명',
    customer_service_phone VARCHAR(20) NOT NULL COMMENT '고객센터 연락처 (하이픈 포함)',
    introduction VARCHAR(200) NULL COMMENT '상점 소개',
    profile_image_key VARCHAR(255) NULL COMMENT '프로필 사진 S3 object key. 없으면 화면에서 상호 첫 글자를 보여 준다',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_seller_profile_member_id UNIQUE (member_id),
    CONSTRAINT fk_seller_profile_member_id FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
