-- 리뷰 분석 요청. (리뷰, 분석 종류)마다 하나
CREATE TABLE review_analysis_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    analysis_type VARCHAR(20) NOT NULL COMMENT 'SENTIMENT',
    status VARCHAR(20) NOT NULL COMMENT 'PENDING, COMPLETED, FAILED(최종 실패), SKIPPED(내용 없는 리뷰라 분석 안 함), CANCELLED(리뷰 삭제)',
    attempt_count INT NOT NULL COMMENT '실패한 시도 횟수',
    next_attempt_at DATETIME(6) NOT NULL COMMENT '이 시각이 지난 대기 요청만 작업자가 꺼낸다',
    last_error VARCHAR(500) NULL COMMENT '마지막 실패 원인. 운영자 확인용',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_analysis_request_review_id_analysis_type UNIQUE (review_id, analysis_type),
    CONSTRAINT fk_review_analysis_request_review_id FOREIGN KEY (review_id) REFERENCES product_review (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 작업자가 분석 종류별로 다음 시도 시각이 지난 대기 요청을 꺼낸다
-- 분석 종류가 앞에 없으면 꺼내기(FOR UPDATE)가 훑는 범위에 다른 분석 종류의 행도 들어가 함께 잠근다
CREATE INDEX idx_review_analysis_request_analysis_type_status_next_attempt_at ON review_analysis_request (analysis_type, status, next_attempt_at);

-- 리뷰의 최신 감성 분석 결과. 리뷰마다 한 행
CREATE TABLE review_sentiment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    review_updated_at DATETIME(6) NOT NULL COMMENT '분석할 때 읽은 리뷰의 수정 시각',
    overall VARCHAR(20) NOT NULL COMMENT '전체 감성. POSITIVE, NEGATIVE, NEUTRAL, MIXED',
    criteria_version VARCHAR(20) NOT NULL COMMENT '분석에 쓴 분류 기준(criteria.json) 버전',
    model VARCHAR(50) NOT NULL COMMENT '분석에 쓴 모델',
    analyzed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_sentiment_review_id UNIQUE (review_id),
    CONSTRAINT fk_review_sentiment_review_id FOREIGN KEY (review_id) REFERENCES product_review (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 언급된 분석 항목의 감성. 언급 없음은 행이 없다
CREATE TABLE review_aspect_sentiment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    review_sentiment_id BIGINT NOT NULL,
    aspect VARCHAR(20) NOT NULL COMMENT 'TASTE, FRESHNESS, CONDITION, PACKAGING, DELIVERY, PRICE',
    sentiment VARCHAR(20) NOT NULL COMMENT 'POSITIVE, NEGATIVE, NEUTRAL, MIXED',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_aspect_sentiment_review_sentiment_id_aspect UNIQUE (review_sentiment_id, aspect),
    CONSTRAINT fk_review_aspect_sentiment_review_sentiment_id FOREIGN KEY (review_sentiment_id) REFERENCES review_sentiment (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
