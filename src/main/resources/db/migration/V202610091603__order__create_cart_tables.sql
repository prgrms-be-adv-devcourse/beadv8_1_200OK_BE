-- 주문 모듈의 회원 정보 복사본
CREATE TABLE market_member (
                               id          BIGINT      NOT NULL COMMENT '회원 모듈의 회원 ID',
                               purchasable BOOLEAN     NOT NULL DEFAULT TRUE COMMENT '구매 가능 여부',
                               created_at  DATETIME(6) NOT NULL,
                               updated_at  DATETIME(6) NOT NULL,
                               deleted_at  DATETIME(6),
                               PRIMARY KEY (id),
                               UNIQUE KEY uk_cart_member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 장바구니 (회원당 1개)
CREATE TABLE cart (
                      id         BIGINT      NOT NULL AUTO_INCREMENT,
                      member_id  BIGINT      NOT NULL COMMENT '회원 ID',
                      created_at DATETIME(6) NOT NULL,
                      updated_at DATETIME(6) NOT NULL,
                      deleted_at DATETIME(6),
                      PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 장바구니 상품
CREATE TABLE cart_item (
                           id                BIGINT      NOT NULL AUTO_INCREMENT,
                           cart_id           BIGINT      NOT NULL,
                           product_id        BIGINT      NOT NULL COMMENT '상품 모듈 ID',
                           product_option_id BIGINT      NOT NULL COMMENT '상품 옵션 모듈 ID',
                           quantity          INT         NOT NULL,
                           selected          BOOLEAN     NOT NULL DEFAULT TRUE COMMENT '주문할 상품 선택 여부',
                           created_at        DATETIME(6) NOT NULL,
                           updated_at        DATETIME(6) NOT NULL,
                           deleted_at        DATETIME(6),
                           PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;