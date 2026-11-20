-- 관심상품(찜) (#817): 회원이 찜한 상품 1개당 1줄
-- 상품 정보는 저장하지 않고 product_id만 둔다. 목록 화면은 프론트가 상품 카드 조회 API(#816)로 붙인다
-- 해제는 행을 지우지 않고 deleted_at을 채운다. 다시 찜하면 같은 행을 되살려 (member_id, product_id) 유니크를 지킨다
CREATE TABLE member_product_wish (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL COMMENT '상품 모듈 ID. 등록할 때 상품이 있는지 검사하지 않는다',
    wished_at DATETIME(6) NOT NULL COMMENT '찜한 시각. 해제 후 다시 찜하면 그 시각으로 바뀐다(목록 정렬 기준)',
    deleted_at DATETIME(6) NULL COMMENT '찜을 해제한 시각. 찜한 상태면 NULL',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_member_product_wish_member_id_product_id UNIQUE (member_id, product_id),
    CONSTRAINT fk_member_product_wish_member_id FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 내 찜 목록을 최근 순(wished_at DESC, id DESC)으로 읽는 조회용. id를 넣어 같은 시각의 행도 인덱스 순서로 읽는다. 해제한 행(deleted_at)은 읽은 행에서 거른다
CREATE INDEX idx_member_product_wish_member_id_wished_at_id ON member_product_wish (member_id, wished_at, id);
