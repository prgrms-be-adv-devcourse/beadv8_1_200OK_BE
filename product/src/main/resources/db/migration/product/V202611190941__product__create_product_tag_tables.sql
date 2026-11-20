-- 상품의 상황·용도 태그 판정 상태이자 판정 대기열. 상품마다 한 행이고 다시 판정할 때도 이 행을 고친다
-- 판정 결과는 상품 행에 두지 않는다. 판정이 비동기로 끝날 때 상품 version이 오르면 그동안 판매자의 상품 수정이 동시 수정으로 실패한다
CREATE TABLE product_tag_judgment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL COMMENT 'PENDING, COMPLETED, FAILED(최종 실패)',
    tag_version VARCHAR(20) NULL COMMENT '마지막 판정에 쓴 태그 목록(tags.json) 버전',
    model VARCHAR(50) NULL COMMENT '마지막 판정에 쓴 모델',
    attempt_count INT NOT NULL COMMENT '이번 요청에서 실패한 시도 횟수',
    next_attempt_at DATETIME(6) NOT NULL COMMENT '이 시각이 지난 대기 요청만 작업자가 꺼낸다. 꺼낼 때 임대 시간만큼 미룬다',
    requested_at DATETIME(6) NOT NULL COMMENT '판정을 요청한 시각. 판정하는 동안 상품이 다시 바뀌었는지 이 값으로 가린다',
    judged_at DATETIME(6) NULL COMMENT '마지막으로 판정을 마친 시각',
    top_tag_id VARCHAR(10) NULL COMMENT '마지막 판정에서 확률이 가장 높았던 태그 ID. 태그가 0개일 때 아깝게 떨어졌는지 보는 근거',
    top_score DOUBLE NULL COMMENT 'top_tag_id의 해당 확률 (0~1)',
    last_error VARCHAR(500) NULL COMMENT '마지막 실패 원인. 운영자 확인용이고 Jev 응답 원문은 담지 않는다',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_product_tag_judgment_product_id UNIQUE (product_id),
    CONSTRAINT fk_product_tag_judgment_product_id FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 작업자가 다음 시도 시각이 지난 대기 요청을 꺼낸다
CREATE INDEX idx_product_tag_judgment_status_next_attempt_at ON product_tag_judgment (status, next_attempt_at);

-- 상품에 붙은 태그. 마지막 판정에서 확률 0.9 이상인 태그만 행이 있다
CREATE TABLE product_tag (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    tag_id VARCHAR(10) NOT NULL COMMENT 'tags.json의 태그 ID',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_product_tag_product_id_tag_id UNIQUE (product_id, tag_id),
    CONSTRAINT fk_product_tag_product_id FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 태그 칩·필터(F10)가 태그로 상품을 찾는다
CREATE INDEX idx_product_tag_tag_id ON product_tag (tag_id);
