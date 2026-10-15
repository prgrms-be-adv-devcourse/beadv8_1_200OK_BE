-- 이미지 URL 대신 media 모듈의 objectKey를 저장. URL은 조회할 때 media 모듈이 만든다
ALTER TABLE product_image
    CHANGE COLUMN url object_key VARCHAR(255) NOT NULL COMMENT 'media 모듈의 objectKey';

ALTER TABLE product
    CHANGE COLUMN thumbnail_url thumbnail_key VARCHAR(255) NOT NULL COMMENT '대표 이미지 objectKey';
