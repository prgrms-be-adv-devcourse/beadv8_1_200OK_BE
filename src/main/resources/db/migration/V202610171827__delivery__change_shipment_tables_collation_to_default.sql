-- shipment 계열 테이블의 collation을 문자셋 기본값으로 되돌린다.
-- 처음 만들 때 지갑 마이그레이션을 따라 utf8mb4_unicode_ci를 명시했지만, 컨벤션은 collation을 DB 기본값에 맡긴다.
-- 다른 테이블과 collation이 다르면 문자열 컬럼을 조인·비교할 때 "Illegal mix of collations" 오류가 나므로 맞춘다.
ALTER TABLE shipment CONVERT TO CHARACTER SET utf8mb4;
ALTER TABLE shipment_item CONVERT TO CHARACTER SET utf8mb4;
ALTER TABLE shipment_address_change_request CONVERT TO CHARACTER SET utf8mb4;
