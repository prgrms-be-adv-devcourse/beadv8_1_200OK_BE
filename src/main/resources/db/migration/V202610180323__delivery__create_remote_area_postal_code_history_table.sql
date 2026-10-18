-- 도서산간 우편번호 변경 이력 (추가만 하고 수정·삭제하지 않는다)
-- 그룹과 소요일은 변경 직후 값만 둔다. 되살림은 항상 삭제 다음이라 변경 전 값은 바로 앞 DELETED 이력에 있다.
CREATE TABLE remote_area_postal_code_history (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    postal_code         VARCHAR(5)  NOT NULL,
    action              VARCHAR(20) NOT NULL COMMENT '변경 동작: REGISTERED, DELETED, RESTORED',
    region_group        VARCHAR(30) NOT NULL COMMENT '변경 직후(삭제는 삭제 시점)의 지역 그룹 코드',
    extra_delivery_days INT         NOT NULL COMMENT '변경 직후(삭제는 삭제 시점)의 추가 소요일(영업일)',
    changed_by          BIGINT      NOT NULL COMMENT '변경한 관리자의 회원 모듈 ID',
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_remote_area_postal_code_history_postal_code FOREIGN KEY (postal_code) REFERENCES remote_area_postal_code (postal_code),
    CONSTRAINT fk_remote_area_postal_code_history_region_group FOREIGN KEY (region_group) REFERENCES region_group (code),
    CONSTRAINT ck_remote_area_postal_code_history_extra_delivery_days CHECK (extra_delivery_days >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
