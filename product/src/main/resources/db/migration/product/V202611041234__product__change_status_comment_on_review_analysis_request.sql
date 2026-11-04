-- 리뷰 수정·삭제·내용 없는 리뷰를 반영하며 분석 요청 상태에 SKIPPED, CANCELLED가 생겼다 (#386)
-- 운영자가 SQL로 상태를 되돌릴 때 볼 수 있게 설명만 바꾼다. 타입과 제약은 그대로다
ALTER TABLE review_analysis_request
    MODIFY COLUMN status VARCHAR(20) NOT NULL COMMENT 'PENDING, COMPLETED, FAILED(최종 실패), SKIPPED(내용 없는 리뷰라 분석 안 함), CANCELLED(리뷰 삭제)';
