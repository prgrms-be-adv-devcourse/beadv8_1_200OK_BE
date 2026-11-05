-- 리뷰의 최신 키워드 분석 결과. 리뷰마다 한 행
-- 키워드를 하나도 표현하지 않은 리뷰도 행이 있어야 분석 완료 리뷰 수(분모)에 든다
CREATE TABLE review_keyword (
    id BIGINT NOT NULL AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    review_updated_at DATETIME(6) NOT NULL COMMENT '분석할 때 읽은 리뷰의 수정 시각',
    keyword_version VARCHAR(20) NOT NULL COMMENT '분석에 쓴 키워드 목록(keywords.json) 버전',
    model VARCHAR(50) NOT NULL COMMENT '분석에 쓴 모델',
    analyzed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_keyword_review_id UNIQUE (review_id),
    CONSTRAINT fk_review_keyword_review_id FOREIGN KEY (review_id) REFERENCES product_review (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 리뷰가 표현한 키워드. 확률 0.9 이상이고 식감 규칙을 적용한 뒤 남은 키워드만 행이 있다
CREATE TABLE review_expressed_keyword (
    id BIGINT NOT NULL AUTO_INCREMENT,
    review_keyword_id BIGINT NOT NULL,
    keyword_id VARCHAR(10) NOT NULL COMMENT 'keywords.json의 키워드 ID',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_expressed_keyword_review_keyword_id_keyword_id UNIQUE (review_keyword_id, keyword_id),
    CONSTRAINT fk_review_expressed_keyword_review_keyword_id FOREIGN KEY (review_keyword_id) REFERENCES review_keyword (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 리뷰 목록의 키워드 필터가 키워드로 결과를 찾는다
CREATE INDEX idx_review_expressed_keyword_keyword_id ON review_expressed_keyword (keyword_id);

-- 운영자가 SQL로 요청을 볼 때 분석 종류 목록을 주석에서 보게 한다. MODIFY는 칼럼 정의를 통째로 다시 쓰므로 타입·NULL 여부를 기존과 같게 적는다
ALTER TABLE review_analysis_request
    MODIFY analysis_type VARCHAR(20) NOT NULL COMMENT 'SENTIMENT, KEYWORD';
