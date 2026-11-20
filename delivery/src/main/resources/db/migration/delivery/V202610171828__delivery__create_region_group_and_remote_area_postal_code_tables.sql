-- 지역 그룹 (도서산간 6종, 일반 지역 GENERAL은 행을 두지 않는다)
CREATE TABLE region_group (
    code       VARCHAR(30) NOT NULL COMMENT '지역 그룹 코드, shipment.region_group에 저장되는 값',
    name       VARCHAR(50) NOT NULL COMMENT '표시 이름',
    surcharge  INT         NOT NULL COMMENT '추가 운임(원)',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (code),
    CONSTRAINT ck_region_group_surcharge CHECK (surcharge >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 도서산간 우편번호
CREATE TABLE remote_area_postal_code (
    postal_code         VARCHAR(5)  NOT NULL COMMENT '우편번호(구역번호) 5자리',
    region_group        VARCHAR(30) NOT NULL COMMENT '지역 그룹 코드',
    extra_delivery_days INT         NOT NULL COMMENT '추가 소요일(영업일)',
    deleted_at          DATETIME(6) NULL,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (postal_code),
    CONSTRAINT fk_remote_area_postal_code_region_group FOREIGN KEY (region_group) REFERENCES region_group (code),
    CONSTRAINT ck_remote_area_postal_code_postal_code CHECK (postal_code REGEXP '^[0-9]{5}$'),
    CONSTRAINT ck_remote_area_postal_code_extra_delivery_days CHECK (extra_delivery_days >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
