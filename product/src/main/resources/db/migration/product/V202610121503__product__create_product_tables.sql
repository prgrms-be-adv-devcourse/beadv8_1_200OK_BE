-- 상품
CREATE TABLE product (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    seller_id            BIGINT       NOT NULL COMMENT '판매자 회원 ID',
    name                 VARCHAR(100) NOT NULL,
    description_html     MEDIUMTEXT   NOT NULL COMMENT '상세 설명 HTML (정제 후)',
    category             VARCHAR(30)  NOT NULL COMMENT '하위 카테고리',
    region               VARCHAR(30)  NOT NULL COMMENT '산지 (시·도)',
    status               VARCHAR(20)  NOT NULL DEFAULT 'READY' COMMENT 'READY, ON_SALE, PAUSED, ENDED',
    visibility           VARCHAR(20)  NOT NULL DEFAULT 'HIDDEN' COMMENT 'VISIBLE, HIDDEN',
    min_sale_price       BIGINT       NOT NULL COMMENT '옵션 최저 판매가',
    min_price_list_price BIGINT       NOT NULL COMMENT 'min_sale_price 옵션의 정가',
    thumbnail_url        VARCHAR(500) NOT NULL COMMENT '대표 이미지 URL',
    review_count         INT          NOT NULL DEFAULT 0,
    rating_sum           INT          NOT NULL DEFAULT 0,
    version              INT          NOT NULL DEFAULT 0 COMMENT '동시 수정 감지',
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    deleted_at           DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_product_seller (seller_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 상품 옵션 (판매·재고 단위)
CREATE TABLE product_option (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    product_id         BIGINT       NOT NULL,
    name               VARCHAR(100) NOT NULL,
    list_price         BIGINT       NOT NULL COMMENT '정가',
    sale_price         BIGINT       NOT NULL COMMENT '판매가',
    total_stock        INT          NOT NULL DEFAULT 0 COMMENT '총재고 (판매자 입력)',
    allocated_quantity INT          NOT NULL DEFAULT 0 COMMENT '배정 수량 (주문 배정·해제)',
    sort_order         INT          NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    deleted_at         DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_product_option_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 상품 이미지
CREATE TABLE product_image (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    product_id BIGINT       NOT NULL,
    url        VARCHAR(500) NOT NULL,
    sort_order INT          NOT NULL COMMENT '0번이 대표 이미지',
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_product_image_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 주문별 재고 배정 기록 (멱등 배정·해제)
CREATE TABLE product_stock_allocation (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    order_id          BIGINT      NOT NULL COMMENT '주문 모듈 주문 ID',
    product_option_id BIGINT      NOT NULL,
    quantity          INT         NOT NULL,
    status            VARCHAR(20) NOT NULL COMMENT 'ALLOCATED, RELEASED',
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_allocation_order_option (order_id, product_option_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
