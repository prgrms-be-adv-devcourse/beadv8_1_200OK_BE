-- 모듈별 스키마 이전(#288). 파일 안의 __DB__, __APP_USER__는 migrate.sh가 값으로 바꿔 실행한다.
-- 앱을 멈춘 상태에서 한 번만 실행한다. 다시 실행하면 사전 점검(e)이 막는다.

-- 열린 트랜잭션이 있으면 RENAME이 메타데이터 락을 기다리므로 오래 기다리지 않고 실패시킨다
SET SESSION lock_wait_timeout = 10;

CREATE DATABASE IF NOT EXISTS `paldogotgan_member` DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS `paldogotgan_wallet` DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS `paldogotgan_order` DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS `paldogotgan_product` DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS `paldogotgan_delivery` DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS `paldogotgan_payout` DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS `paldogotgan_media` DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS `paldogotgan_refund` DEFAULT CHARACTER SET utf8mb4;
GRANT ALL PRIVILEGES ON `paldogotgan\_%`.* TO '__APP_USER__'@'%';

-- 한 문장이라 전부 성공하거나 전부 실패한다. 데이터를 복사하지 않고 메타데이터만 옮긴다(인덱스, AUTO_INCREMENT, 외래키 유지)
RENAME TABLE
    __DB__.`member` TO `paldogotgan_member`.`member`,
    __DB__.`refresh_token` TO `paldogotgan_member`.`refresh_token`,
    __DB__.`seller_application` TO `paldogotgan_member`.`seller_application`,
    __DB__.`seller_profile` TO `paldogotgan_member`.`seller_profile`,
    __DB__.`buyer_wallet` TO `paldogotgan_wallet`.`buyer_wallet`,
    __DB__.`buyer_wallet_ledger` TO `paldogotgan_wallet`.`buyer_wallet_ledger`,
    __DB__.`seller_wallet` TO `paldogotgan_wallet`.`seller_wallet`,
    __DB__.`seller_wallet_ledger` TO `paldogotgan_wallet`.`seller_wallet_ledger`,
    __DB__.`wallet_charge` TO `paldogotgan_wallet`.`wallet_charge`,
    __DB__.`wallet_order_payment` TO `paldogotgan_wallet`.`wallet_order_payment`,
    __DB__.`wallet_terms` TO `paldogotgan_wallet`.`wallet_terms`,
    __DB__.`wallet_terms_agreement` TO `paldogotgan_wallet`.`wallet_terms_agreement`,
    __DB__.`cart` TO `paldogotgan_order`.`cart`,
    __DB__.`cart_item` TO `paldogotgan_order`.`cart_item`,
    __DB__.`order_item` TO `paldogotgan_order`.`order_item`,
    __DB__.`order_seller_group` TO `paldogotgan_order`.`order_seller_group`,
    __DB__.`order_third_party_consent` TO `paldogotgan_order`.`order_third_party_consent`,
    __DB__.`orders` TO `paldogotgan_order`.`orders`,
    __DB__.`product` TO `paldogotgan_product`.`product`,
    __DB__.`product_image` TO `paldogotgan_product`.`product_image`,
    __DB__.`product_option` TO `paldogotgan_product`.`product_option`,
    __DB__.`product_review` TO `paldogotgan_product`.`product_review`,
    __DB__.`product_review_media` TO `paldogotgan_product`.`product_review_media`,
    __DB__.`product_stock_allocation` TO `paldogotgan_product`.`product_stock_allocation`,
    __DB__.`default_shipping_address` TO `paldogotgan_delivery`.`default_shipping_address`,
    __DB__.`region_group` TO `paldogotgan_delivery`.`region_group`,
    __DB__.`remote_area_postal_code` TO `paldogotgan_delivery`.`remote_area_postal_code`,
    __DB__.`remote_area_postal_code_history` TO `paldogotgan_delivery`.`remote_area_postal_code_history`,
    __DB__.`seller_shipping_profile` TO `paldogotgan_delivery`.`seller_shipping_profile`,
    __DB__.`shipment` TO `paldogotgan_delivery`.`shipment`,
    __DB__.`shipment_address_change_request` TO `paldogotgan_delivery`.`shipment_address_change_request`,
    __DB__.`shipment_item` TO `paldogotgan_delivery`.`shipment_item`,
    __DB__.`BATCH_JOB_EXECUTION` TO `paldogotgan_payout`.`BATCH_JOB_EXECUTION`,
    __DB__.`BATCH_JOB_EXECUTION_CONTEXT` TO `paldogotgan_payout`.`BATCH_JOB_EXECUTION_CONTEXT`,
    __DB__.`BATCH_JOB_EXECUTION_PARAMS` TO `paldogotgan_payout`.`BATCH_JOB_EXECUTION_PARAMS`,
    __DB__.`BATCH_JOB_EXECUTION_SEQ` TO `paldogotgan_payout`.`BATCH_JOB_EXECUTION_SEQ`,
    __DB__.`BATCH_JOB_INSTANCE` TO `paldogotgan_payout`.`BATCH_JOB_INSTANCE`,
    __DB__.`BATCH_JOB_INSTANCE_SEQ` TO `paldogotgan_payout`.`BATCH_JOB_INSTANCE_SEQ`,
    __DB__.`BATCH_STEP_EXECUTION` TO `paldogotgan_payout`.`BATCH_STEP_EXECUTION`,
    __DB__.`BATCH_STEP_EXECUTION_CONTEXT` TO `paldogotgan_payout`.`BATCH_STEP_EXECUTION_CONTEXT`,
    __DB__.`BATCH_STEP_EXECUTION_SEQ` TO `paldogotgan_payout`.`BATCH_STEP_EXECUTION_SEQ`,
    __DB__.`payout` TO `paldogotgan_payout`.`payout`,
    __DB__.`payout_candidate_items` TO `paldogotgan_payout`.`payout_candidate_items`,
    __DB__.`payout_failure` TO `paldogotgan_payout`.`payout_failure`,
    __DB__.`payout_item` TO `paldogotgan_payout`.`payout_item`,
    __DB__.`payout_seller_draft` TO `paldogotgan_payout`.`payout_seller_draft`,
    __DB__.`payout_seller_draft_item` TO `paldogotgan_payout`.`payout_seller_draft_item`,
    __DB__.`payout_snapshot` TO `paldogotgan_payout`.`payout_snapshot`,
    __DB__.`payout_snapshot_item` TO `paldogotgan_payout`.`payout_snapshot_item`,
    __DB__.`payout_system_draft_item` TO `paldogotgan_payout`.`payout_system_draft_item`,
    __DB__.`payout_wallet_deduction` TO `paldogotgan_payout`.`payout_wallet_deduction`,
    __DB__.`media` TO `paldogotgan_media`.`media`;

-- Flyway 이력: 적용된 행을 값 그대로(버전, 설명, 체크섬, 순번) 모듈 스키마 이력으로 복사하고 기본 스키마 이력에서 지운다
CREATE TABLE `paldogotgan_member`.flyway_schema_history LIKE __DB__.flyway_schema_history;
CREATE TABLE `paldogotgan_wallet`.flyway_schema_history LIKE __DB__.flyway_schema_history;
CREATE TABLE `paldogotgan_order`.flyway_schema_history LIKE __DB__.flyway_schema_history;
CREATE TABLE `paldogotgan_product`.flyway_schema_history LIKE __DB__.flyway_schema_history;
CREATE TABLE `paldogotgan_delivery`.flyway_schema_history LIKE __DB__.flyway_schema_history;
CREATE TABLE `paldogotgan_payout`.flyway_schema_history LIKE __DB__.flyway_schema_history;
CREATE TABLE `paldogotgan_media`.flyway_schema_history LIKE __DB__.flyway_schema_history;

START TRANSACTION;
INSERT INTO `paldogotgan_member`.flyway_schema_history
    SELECT * FROM __DB__.flyway_schema_history WHERE script LIKE 'V%\_\_member\_\_%';
INSERT INTO `paldogotgan_wallet`.flyway_schema_history
    SELECT * FROM __DB__.flyway_schema_history WHERE script LIKE 'V%\_\_wallet\_\_%';
INSERT INTO `paldogotgan_order`.flyway_schema_history
    SELECT * FROM __DB__.flyway_schema_history WHERE script LIKE 'V%\_\_order\_\_%';
INSERT INTO `paldogotgan_product`.flyway_schema_history
    SELECT * FROM __DB__.flyway_schema_history WHERE script LIKE 'V%\_\_product\_\_%';
INSERT INTO `paldogotgan_delivery`.flyway_schema_history
    SELECT * FROM __DB__.flyway_schema_history WHERE script LIKE 'V%\_\_delivery\_\_%';
INSERT INTO `paldogotgan_payout`.flyway_schema_history
    SELECT * FROM __DB__.flyway_schema_history WHERE (script LIKE 'V%\_\_payout\_\_%' OR script = 'V202610092032__common__create_batch_metadata_tables.sql');
INSERT INTO `paldogotgan_media`.flyway_schema_history
    SELECT * FROM __DB__.flyway_schema_history WHERE script LIKE 'V%\_\_media\_\_%';
DELETE FROM __DB__.flyway_schema_history WHERE script <> 'V202610081120__common__create_event_publication_table.sql';
COMMIT;
