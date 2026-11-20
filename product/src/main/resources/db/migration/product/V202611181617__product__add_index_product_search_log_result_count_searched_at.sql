-- 0건 검색어 조회(F12)는 결과 수가 0인 행을 기간으로 거른다. 검색 시각 인덱스만으로는 기간 안의 모든 검색을 읽어야 한다
CREATE INDEX idx_product_search_log_result_count_searched_at ON product_search_log (result_count, searched_at);
