-- 상품 검색용 정규화 컬럼. 상품과 1:1이며, 태그 판정이 비동기로 keywords를 고쳐도 상품 version이 바뀌지 않게 따로 둔다
-- 행은 여기서 넣지 않는다. 자모 분해가 앱 코드라서 상품 등록·수정과 기동 시 백필이 채운다
CREATE TABLE product_search (
    product_id         BIGINT       NOT NULL COMMENT '상품 ID',
    name_compact       VARCHAR(100) NOT NULL COMMENT '정규화 후 공백을 없앤 상품명',
    name_jamo          VARCHAR(400) NOT NULL COMMENT 'name_compact 자모 분해',
    search_body        TEXT         NOT NULL COMMENT '옵션명과 카테고리 한글명',
    keywords           TEXT         NOT NULL COMMENT '태그와 동의어',
    search_item        VARCHAR(20)  NULL     COMMENT '품목 사전에서 찾은 가장 긴 품목. 1글자 검색어용',
    normalizer_version INT          NOT NULL COMMENT '이 행을 만든 정규화 함수 버전',
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (product_id),
    KEY idx_product_search_search_item (search_item),
    KEY idx_product_search_normalizer_version (normalizer_version),
    FULLTEXT KEY ft_product_search_name_compact (name_compact) WITH PARSER ngram,
    FULLTEXT KEY ft_product_search_name_jamo (name_jamo) WITH PARSER ngram,
    FULLTEXT KEY ft_product_search_search_body (search_body) WITH PARSER ngram,
    FULLTEXT KEY ft_product_search_keywords (keywords) WITH PARSER ngram,
    CONSTRAINT fk_product_search_product_id FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
