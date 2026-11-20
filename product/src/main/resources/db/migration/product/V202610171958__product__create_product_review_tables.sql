-- 상품 리뷰
CREATE TABLE product_review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    product_option_id BIGINT NOT NULL COMMENT '구매한 옵션',
    order_id BIGINT NOT NULL COMMENT '주문 모듈 주문 ID',
    order_item_id BIGINT NOT NULL COMMENT '주문 모듈 주문상품 ID. 리뷰를 쓴 옵션 표시용',
    member_id BIGINT NOT NULL COMMENT '작성자 회원 ID',
    rating TINYINT NOT NULL COMMENT '별점. 0.5점 단위라 1~10으로 저장 (7 = 3.5점)',
    content VARCHAR(2000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    -- 한 주문에서 같은 상품은 옵션을 여러 개 사도 리뷰 1개. 다른 주문(재구매)이면 다시 쓸 수 있다
    -- 삭제한 리뷰도 행이 남으므로 같은 주문의 같은 상품으로 다시 쓸 수 없다
    CONSTRAINT uk_product_review_order_id_product_id UNIQUE (order_id, product_id),
    CONSTRAINT ck_product_review_rating CHECK (rating BETWEEN 1 AND 10),
    CONSTRAINT fk_product_review_product_id FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT fk_product_review_product_option_id FOREIGN KEY (product_option_id) REFERENCES product_option (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 상품별 리뷰 목록 최신순
CREATE INDEX idx_product_review_product_id_id ON product_review (product_id, id);
-- 내가 쓴 리뷰 목록
CREATE INDEX idx_product_review_member_id_id ON product_review (member_id, id);

-- 리뷰 미디어 경로
CREATE TABLE product_review_media (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_review_id BIGINT NOT NULL,
    object_key VARCHAR(255) NOT NULL COMMENT '미디어 모듈 objectKey',
    media_type VARCHAR(10) NOT NULL COMMENT 'IMAGE, VIDEO',
    sort_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_product_review_media_product_review_id FOREIGN KEY (product_review_id) REFERENCES product_review (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
