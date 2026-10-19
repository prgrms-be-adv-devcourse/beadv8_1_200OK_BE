-- 관리자 신청 목록은 대부분 "심사 대기 신청을 오래된 순으로" 조회한다. 상태 필터와 신청 일시 정렬을 한 인덱스로 처리한다.
CREATE INDEX idx_seller_application_status_applied_at ON seller_application (status, applied_at);
