-- 모듈별 스키마 이전 사전 점검(#288). 파일 안의 __DB__, __APP_USER__는 migrate.sh가 값으로 바꿔 실행한다.
-- 출력 한 줄이 점검 하나다: CHECK <이름> <PASS|FAIL> <상세>. FAIL이 하나라도 있으면 migrate.sh가 중단한다.

-- (a) 기본 스키마의 테이블이 이전 대상 목록(tables.txt)과 정확히 같다(event_publication, flyway_schema_history 제외)
SELECT 'tables_match', IF(COUNT(*) = 0, 'PASS', 'FAIL'), IFNULL(GROUP_CONCAT(name), '')
FROM (
    SELECT table_name AS name FROM information_schema.tables
    WHERE table_schema = '__DB__' AND table_name NOT IN ('event_publication', 'flyway_schema_history')
      AND table_name NOT IN ('member','refresh_token','seller_application','seller_profile','buyer_wallet','buyer_wallet_ledger','seller_wallet','seller_wallet_ledger','wallet_charge','wallet_order_payment','wallet_terms','wallet_terms_agreement','cart','cart_item','order_item','order_seller_group','order_third_party_consent','orders','product','product_image','product_option','product_review','product_review_media','product_stock_allocation','default_shipping_address','region_group','remote_area_postal_code','remote_area_postal_code_history','seller_shipping_profile','shipment','shipment_address_change_request','shipment_item','BATCH_JOB_EXECUTION','BATCH_JOB_EXECUTION_CONTEXT','BATCH_JOB_EXECUTION_PARAMS','BATCH_JOB_EXECUTION_SEQ','BATCH_JOB_INSTANCE','BATCH_JOB_INSTANCE_SEQ','BATCH_STEP_EXECUTION','BATCH_STEP_EXECUTION_CONTEXT','BATCH_STEP_EXECUTION_SEQ','payout','payout_candidate_items','payout_failure','payout_item','payout_seller_draft','payout_seller_draft_item','payout_snapshot','payout_snapshot_item','payout_system_draft_item','payout_wallet_deduction','media')
    UNION ALL
    SELECT e.name FROM (SELECT 'member' AS name UNION ALL SELECT 'refresh_token' UNION ALL SELECT 'seller_application' UNION ALL SELECT 'seller_profile' UNION ALL SELECT 'buyer_wallet' UNION ALL SELECT 'buyer_wallet_ledger' UNION ALL SELECT 'seller_wallet' UNION ALL SELECT 'seller_wallet_ledger' UNION ALL SELECT 'wallet_charge' UNION ALL SELECT 'wallet_order_payment' UNION ALL SELECT 'wallet_terms' UNION ALL SELECT 'wallet_terms_agreement' UNION ALL SELECT 'cart' UNION ALL SELECT 'cart_item' UNION ALL SELECT 'order_item' UNION ALL SELECT 'order_seller_group' UNION ALL SELECT 'order_third_party_consent' UNION ALL SELECT 'orders' UNION ALL SELECT 'product' UNION ALL SELECT 'product_image' UNION ALL SELECT 'product_option' UNION ALL SELECT 'product_review' UNION ALL SELECT 'product_review_media' UNION ALL SELECT 'product_stock_allocation' UNION ALL SELECT 'default_shipping_address' UNION ALL SELECT 'region_group' UNION ALL SELECT 'remote_area_postal_code' UNION ALL SELECT 'remote_area_postal_code_history' UNION ALL SELECT 'seller_shipping_profile' UNION ALL SELECT 'shipment' UNION ALL SELECT 'shipment_address_change_request' UNION ALL SELECT 'shipment_item' UNION ALL SELECT 'BATCH_JOB_EXECUTION' UNION ALL SELECT 'BATCH_JOB_EXECUTION_CONTEXT' UNION ALL SELECT 'BATCH_JOB_EXECUTION_PARAMS' UNION ALL SELECT 'BATCH_JOB_EXECUTION_SEQ' UNION ALL SELECT 'BATCH_JOB_INSTANCE' UNION ALL SELECT 'BATCH_JOB_INSTANCE_SEQ' UNION ALL SELECT 'BATCH_STEP_EXECUTION' UNION ALL SELECT 'BATCH_STEP_EXECUTION_CONTEXT' UNION ALL SELECT 'BATCH_STEP_EXECUTION_SEQ' UNION ALL SELECT 'payout' UNION ALL SELECT 'payout_candidate_items' UNION ALL SELECT 'payout_failure' UNION ALL SELECT 'payout_item' UNION ALL SELECT 'payout_seller_draft' UNION ALL SELECT 'payout_seller_draft_item' UNION ALL SELECT 'payout_snapshot' UNION ALL SELECT 'payout_snapshot_item' UNION ALL SELECT 'payout_system_draft_item' UNION ALL SELECT 'payout_wallet_deduction' UNION ALL SELECT 'media')  e
    WHERE e.name NOT IN (SELECT table_name FROM information_schema.tables WHERE table_schema = '__DB__')
) diff;

-- (b) 이력이 기대하는 55개 마이그레이션과 정확히 같다(파일명 기준). 이전 후 검증·rollback이 55행을 전제로 하므로 누락도 초과도 막는다
USE `__DB__`;
CREATE TEMPORARY TABLE expected_history (name VARCHAR(200) NOT NULL PRIMARY KEY) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
INSERT INTO expected_history (name) VALUES
    ('V202610081120__common__create_event_publication_table.sql'),
    ('V202610081500__media__create_media_table.sql'),
    ('V202610081502__payout__create_payout_candidate_items_table.sql'),
    ('V202610091242__wallet__create_wallet_table.sql'),
    ('V202610091603__order__create_cart_tables.sql'),
    ('V202610091620__member__create_member_table.sql'),
    ('V202610091950__payout__create_payout_and_payout_item_tables.sql'),
    ('V202610092032__common__create_batch_metadata_tables.sql'),
    ('V202610111500__delivery__create_shipment_tables.sql'),
    ('V202610121030__payout__add_is_system_to_payout.sql'),
    ('V202610121434__wallet__rename_wallet_to_buyer_wallet.sql'),
    ('V202610121503__product__create_product_tables.sql'),
    ('V202610121705__order__drop_market_member_table.sql'),
    ('V202610121706__order__drop_product_id_from_cart_item.sql'),
    ('V202610121739__member__create_refresh_token_table.sql'),
    ('V202610131100__wallet__create_seller_wallet_table.sql'),
    ('V202610131205__payout__change_payout_unique_keys.sql'),
    ('V202610141251__payout__create_payout_snapshot_and_draft_tables.sql'),
    ('V202610142354__wallet__create_buyer_wallet_ledger_table.sql'),
    ('V202610151012__wallet__create_seller_wallet_ledger_table.sql'),
    ('V202610151104__product__change_image_url_to_object_key.sql'),
    ('V202610151333__payout__add_index_payout_for_search.sql'),
    ('V202610151700__order__add_unique_to_cart_item.sql'),
    ('V202610161242__wallet__create_wallet_terms_tables.sql'),
    ('V202610170146__order__create_order_tables.sql'),
    ('V202610171827__delivery__change_shipment_tables_collation_to_default.sql'),
    ('V202610171828__delivery__create_region_group_and_remote_area_postal_code_tables.sql'),
    ('V202610171829__delivery__insert_remote_area_postal_codes.sql'),
    ('V202610171958__product__create_product_review_tables.sql'),
    ('V202610180107__member__change_role_to_member_type.sql'),
    ('V202610180323__delivery__create_remote_area_postal_code_history_table.sql'),
    ('V202610191225__wallet__create_wallet_order_payment_table.sql'),
    ('V202610191241__member__create_seller_application_table.sql'),
    ('V202610191336__payout__add_order_id_to_payout_candidate_items.sql'),
    ('V202610191347__payout__add_order_id_to_payout_draft_and_item_tables.sql'),
    ('V202610191421__member__add_index_seller_application_status_applied_at.sql'),
    ('V202610191433__payout__create_payout_wallet_deduction_table.sql'),
    ('V202610191512__wallet__create_wallet_charge_table.sql'),
    ('V202610191627__media__add_member_id_to_media.sql'),
    ('V202610192259__delivery__change_free_shipping_threshold_to_nullable.sql'),
    ('V202610192300__delivery__add_charged_shipping_fee_to_shipment.sql'),
    ('V202610200022__wallet__add_index_to_buyer_wallet_ledger.sql'),
    ('V202610200201__delivery__add_reserved_cancel_quantity_to_shipment_item.sql'),
    ('V202610201001__member__create_seller_profile_table.sql'),
    ('V202610201120__delivery__add_buyer_id_to_shipment.sql'),
    ('V202610201130__order__add_delivered_at_to_order_item.sql'),
    ('V202610201335__payout__add_index_payout_candidate_items_for_search.sql'),
    ('V202610201422__delivery__change_region_surcharge_comment_on_shipment.sql'),
    ('V202610201630__order__add_confirmed_at_to_order_item.sql'),
    ('V202610210012__delivery__add_index_shipment_buyer_id_created_at.sql'),
    ('V202610210136__delivery__add_estimated_ship_date_to_shipment.sql'),
    ('V202610210137__delivery__add_index_shipment_seller_id_created_at.sql'),
    ('V202610210330__delivery__create_default_shipping_address_table.sql'),
    ('V202610231140__delivery__create_seller_shipping_profile_table.sql'),
    ('V202610250958__payout__create_payout_failure_table.sql');

SELECT 'history_known_only', IF(COUNT(*) = 0, 'PASS', 'FAIL'), IFNULL(GROUP_CONCAT(script), '')
FROM __DB__.flyway_schema_history
WHERE CONVERT(SUBSTRING_INDEX(script, '/', -1) USING utf8mb4) COLLATE utf8mb4_bin NOT IN (SELECT name FROM expected_history);

SELECT 'history_none_missing', IF(COUNT(*) = 0, 'PASS', 'FAIL'), IFNULL(GROUP_CONCAT(name), '')
FROM expected_history
WHERE name NOT IN (SELECT CONVERT(SUBSTRING_INDEX(script, '/', -1) USING utf8mb4) COLLATE utf8mb4_bin FROM __DB__.flyway_schema_history WHERE success = 1);

SELECT 'history_exact_55_success', IF(COUNT(*) = 55 AND SUM(success = 1) = 55, 'PASS', 'FAIL'), CONCAT(COUNT(*), '행, 성공 ', IFNULL(SUM(success = 1), 0), '행')
FROM __DB__.flyway_schema_history;

-- (c) 실패한 마이그레이션 이력이 없다
SELECT 'history_no_failed', IF(COUNT(*) = 0, 'PASS', 'FAIL'), COUNT(*)
FROM __DB__.flyway_schema_history WHERE success = 0;

-- (d) 트리거·뷰·테이블 단위 권한이 없다(트리거가 있으면 RENAME이 실패하고, 테이블 권한은 RENAME을 따라가지 않는다)
SELECT 'no_triggers', IF(COUNT(*) = 0, 'PASS', 'FAIL'), COUNT(*)
FROM information_schema.triggers WHERE trigger_schema = '__DB__';
SELECT 'no_views', IF(COUNT(*) = 0, 'PASS', 'FAIL'), COUNT(*)
FROM information_schema.views WHERE table_schema = '__DB__';
SELECT 'no_table_privileges', IF(COUNT(*) = 0, 'PASS', 'FAIL'), COUNT(*)
FROM mysql.tables_priv WHERE Db = '__DB__';

-- (e) 대상 모듈 스키마에 테이블이 없다(이미 이전했거나 앱이 먼저 떠서 빈 테이블을 만든 경우를 막는다)
SELECT 'target_schemas_empty', IF(COUNT(*) = 0, 'PASS', 'FAIL'), IFNULL(GROUP_CONCAT(CONCAT(table_schema, '.', table_name)), '')
FROM information_schema.tables WHERE table_schema LIKE 'paldogotgan\_%';

-- (e-1) 앱 계정에 이스케이프 없이 준 모듈 스키마 권한(paldogotgan_x)이 없다. `_`가 와일드카드라 다른 DB까지 닿고 이전 스크립트의 REVOKE가 거두지 못한다
SELECT 'no_unescaped_module_grants', IF(COUNT(*) = 0, 'PASS', 'FAIL'), IFNULL(GROUP_CONCAT(Db), '')
FROM mysql.db WHERE User = '__APP_USER__' AND Db LIKE 'paldogotgan\\_%';

-- (e-2) 앱이 멈춰 있다: 앱 계정 연결과 열린 트랜잭션이 없다(있으면 RENAME이 메타데이터 락을 기다린다)
SELECT 'no_app_connections', IF(COUNT(*) = 0, 'PASS', 'FAIL'), COUNT(*)
FROM information_schema.processlist WHERE user = '__APP_USER__';
SELECT 'no_open_transactions', IF(COUNT(*) = 0, 'PASS', 'FAIL'), COUNT(*)
FROM information_schema.innodb_trx;

-- (f) 참고: 아직 발행이 끝나지 않은 이벤트 수(0이 좋다. 있어도 이전 후 재발행된다)
SELECT 'INFO', 'event_publication_incomplete', COUNT(*) FROM __DB__.event_publication WHERE completion_date IS NULL;
