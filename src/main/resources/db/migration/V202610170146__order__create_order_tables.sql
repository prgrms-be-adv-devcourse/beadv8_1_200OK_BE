-- 주문 (주문 1건 = 결제 1번)
CREATE TABLE orders (
                        id                    BIGINT       NOT NULL AUTO_INCREMENT,
                        buyer_id              BIGINT       NOT NULL COMMENT '구매자 회원 ID',
                        status                VARCHAR(20)  NOT NULL COMMENT '주문(결제) 상태',
                        recipient_name        VARCHAR(50)  NOT NULL COMMENT '수령인 이름',
                        recipient_phone       VARCHAR(20)  NOT NULL COMMENT '수령인 연락처',
                        zip_code              VARCHAR(5)   NOT NULL COMMENT '우편번호',
                        address               VARCHAR(200) NOT NULL COMMENT '기본 주소',
                        address_detail        VARCHAR(100)          COMMENT '상세 주소',
                        total_product_amount  BIGINT       NOT NULL COMMENT '상품 금액 합계',
                        total_shipping_fee    BIGINT       NOT NULL COMMENT '배송비 합계',
                        total_remote_area_fee BIGINT       NOT NULL COMMENT '도서산간 추가 운임 합계',
                        total_amount          BIGINT       NOT NULL COMMENT '최종 결제 금액',
                        created_at            DATETIME(6)  NOT NULL,
                        updated_at            DATETIME(6)  NOT NULL,
                        PRIMARY KEY (id),
                        KEY idx_orders_buyer (buyer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 판매자별 묶음 (배송 1건)
CREATE TABLE order_seller_group (
                                    id              BIGINT      NOT NULL AUTO_INCREMENT,
                                    order_id        BIGINT      NOT NULL COMMENT '주문 ID',
                                    seller_id       BIGINT      NOT NULL COMMENT '판매자 회원 ID',
                                    shipping_fee    BIGINT      NOT NULL COMMENT '묶음 배송비',
                                    remote_area_fee BIGINT      NOT NULL COMMENT '도서산간 추가 운임',
                                    created_at      DATETIME(6) NOT NULL,
                                    updated_at      DATETIME(6) NOT NULL,
                                    PRIMARY KEY (id),
                                    UNIQUE KEY uk_order_seller_group (order_id, seller_id),
                                    KEY idx_order_seller_group_seller (seller_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 주문 상품 (주문 시점 정보 저장)
CREATE TABLE order_item (
                            id                  BIGINT       NOT NULL AUTO_INCREMENT,
                            seller_group_id     BIGINT       NOT NULL COMMENT '판매자 묶음 ID',
                            product_id          BIGINT       NOT NULL COMMENT '상품 모듈 상품 ID',
                            product_option_id   BIGINT       NOT NULL COMMENT '상품 모듈 옵션 ID',
                            product_name        VARCHAR(100) NOT NULL COMMENT '주문 시점 상품명',
                            product_option_name VARCHAR(100) NOT NULL COMMENT '주문 시점 옵션명',
                            unit_price          BIGINT       NOT NULL COMMENT '주문 시점 판매가',
                            quantity            INT          NOT NULL COMMENT '수량',
                            item_amount         BIGINT       NOT NULL COMMENT '판매가 × 수량',
                            status              VARCHAR(30)  NOT NULL COMMENT '주문 상품 상태',
                            created_at          DATETIME(6)  NOT NULL,
                            updated_at          DATETIME(6)  NOT NULL,
                            PRIMARY KEY (id),
                            KEY idx_order_item_seller_group (seller_group_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 판매자별 주문 정보 제공 동의 (주문 1건 × 판매자 1명 = 1줄)
CREATE TABLE order_third_party_consent (
                                           id         BIGINT      NOT NULL AUTO_INCREMENT,
                                           order_id   BIGINT      NOT NULL COMMENT '주문 ID',
                                           buyer_id   BIGINT      NOT NULL COMMENT '동의한 구매자 회원 ID',
                                           seller_id  BIGINT      NOT NULL COMMENT '정보를 제공받는 판매자 회원 ID',
                                           created_at DATETIME(6) NOT NULL COMMENT '동의 시각',
                                           updated_at DATETIME(6) NOT NULL,
                                           PRIMARY KEY (id),
                                           UNIQUE KEY uk_order_third_party_consent (order_id, seller_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;