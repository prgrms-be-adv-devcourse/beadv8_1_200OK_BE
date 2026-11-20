-- 키워드 분석 전에 쓰인 기존 리뷰도 분석되도록 삭제되지 않은 리뷰마다 키워드 대기 요청을 넣는다
-- 표를 만드는 파일과 나눈 이유: 이 파일만 다시 실행해 백필 결과를 통합 테스트로 확인한다(ReviewKeywordBackfillIntegrationTest)
INSERT INTO review_analysis_request (review_id, analysis_type, status, attempt_count, next_attempt_at, created_at, updated_at)
SELECT id, 'KEYWORD', 'PENDING', 0, NOW(6), NOW(6), NOW(6)
FROM product_review
WHERE deleted_at IS NULL;
