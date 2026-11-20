-- 기본 배송지 (회원당 1개, 주문서 초기값. 바꿔도 기존 주문과 shipment에는 영향이 없다)
CREATE TABLE default_shipping_address (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL COMMENT '회원 모듈 ID',
    recipient_name VARCHAR(50) NOT NULL,
    recipient_phone VARCHAR(11) NOT NULL COMMENT '연락처 (숫자만)',
    postal_code VARCHAR(5) NOT NULL,
    address VARCHAR(255) NOT NULL,
    address_detail VARCHAR(255) NOT NULL,
    delivery_note VARCHAR(255) NULL COMMENT '배송 요청사항 (선택)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_default_shipping_address_member_id UNIQUE (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
