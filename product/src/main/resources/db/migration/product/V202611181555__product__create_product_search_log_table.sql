-- 구매자 키워드 검색 한 번이 한 행이다. 쌓기만 하고 고치지 않으며, 보존 기간이 지난 행은 날마다 지운다
-- 회원을 알 수 있는 값(회원 ID, IP)은 두지 않는다
CREATE TABLE product_search_log (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    keyword            VARCHAR(50) NOT NULL COMMENT '구매자가 입력한 검색어 원문',
    normalized_keyword VARCHAR(50) NOT NULL COMMENT '정규화 후 공백을 없앤 검색어. 띄어쓰기만 다른 검색어를 묶는 기준',
    result_count       BIGINT      NOT NULL COMMENT '페이지 크기와 관계없는 전체 결과 수',
    latency_millis     BIGINT      NOT NULL COMMENT '검색 SQL과 상품 조회에 걸린 시간 (ms). 오타 제안과 로그 저장은 빠진다',
    suggested          BOOLEAN     NOT NULL COMMENT '오타 교정 제안을 함께 보냈는지',
    searched_at        DATETIME(6) NOT NULL COMMENT '검색한 시각. 저장이 비동기라 created_at보다 앞선다',
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_product_search_log_searched_at (searched_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
