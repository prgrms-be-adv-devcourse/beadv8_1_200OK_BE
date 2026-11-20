-- 모듈별 스키마 이전 사후 점검(#288). 출력 형식은 00_preflight.sql과 같다. 행 수·체크섬 비교는 migrate.sh가 한다.

-- 기본 스키마에는 event_publication, 이력, 자리표시 테이블(media)만 남는다
SELECT 'base_tables_only_common', IF(COUNT(*) = 0, 'PASS', 'FAIL'), IFNULL(GROUP_CONCAT(table_name), '')
FROM information_schema.tables
WHERE table_schema = '__DB__' AND table_name NOT IN ('event_publication', 'flyway_schema_history', 'media');
SELECT 'base_placeholder_media', IF(COUNT(*) = 1, 'PASS', 'FAIL'), COUNT(*)
FROM information_schema.tables
WHERE table_schema = '__DB__' AND table_name = 'media' AND table_comment = 'migration-288 placeholder: 이전 전 이미지가 이전된 DB에서 기동하지 않게 하는 자리표시 테이블';
SELECT 'base_history_event_publication_only', IF(COUNT(*) = 1 AND MIN(SUBSTRING_INDEX(script, '/', -1)) = 'V202610081120__common__create_event_publication_table.sql', 'PASS', 'FAIL'), COUNT(*)
FROM __DB__.flyway_schema_history;
SELECT 'member_history_rows', IF(COUNT(*) = 6, 'PASS', 'FAIL'), COUNT(*) FROM `paldogotgan_member`.flyway_schema_history;
SELECT 'wallet_history_rows', IF(COUNT(*) = 9, 'PASS', 'FAIL'), COUNT(*) FROM `paldogotgan_wallet`.flyway_schema_history;
SELECT 'order_history_rows', IF(COUNT(*) = 7, 'PASS', 'FAIL'), COUNT(*) FROM `paldogotgan_order`.flyway_schema_history;
SELECT 'product_history_rows', IF(COUNT(*) = 3, 'PASS', 'FAIL'), COUNT(*) FROM `paldogotgan_product`.flyway_schema_history;
SELECT 'delivery_history_rows', IF(COUNT(*) = 15, 'PASS', 'FAIL'), COUNT(*) FROM `paldogotgan_delivery`.flyway_schema_history;
SELECT 'payout_history_rows', IF(COUNT(*) = 12, 'PASS', 'FAIL'), COUNT(*) FROM `paldogotgan_payout`.flyway_schema_history;
SELECT 'media_history_rows', IF(COUNT(*) = 2, 'PASS', 'FAIL'), COUNT(*) FROM `paldogotgan_media`.flyway_schema_history;

-- 앱 계정에 모듈 스키마 8개의 권한이 정확히 있다(mysql.db에는 GRANT에서 이스케이프한 `\_` 그대로 저장된다)
SELECT 'app_user_grants', IF(COUNT(*) = 8, 'PASS', 'FAIL'), IFNULL(GROUP_CONCAT(Db), '')
FROM mysql.db WHERE User = '__APP_USER__' AND Db IN ('paldogotgan\\_member', 'paldogotgan\\_wallet', 'paldogotgan\\_order', 'paldogotgan\\_product', 'paldogotgan\\_delivery', 'paldogotgan\\_payout', 'paldogotgan\\_media', 'paldogotgan\\_refund');
