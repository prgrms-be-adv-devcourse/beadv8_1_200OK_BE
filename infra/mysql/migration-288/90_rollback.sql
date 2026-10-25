-- 모듈별 스키마 이전 되돌리기 1단계(#288): 이력 복원과 테이블 되돌리기. 앱을 멈춘 상태에서, 이전 후 새 마이그레이션이 적용되지 않았을 때만 쓴다.
-- 모듈 스키마를 지우는 2단계(91_rollback_drop.sql)는 이 단계 뒤 모듈 스키마가 비었는지 확인하고 migrate.sh가 실행한다.
SET SESSION lock_wait_timeout = 10;

START TRANSACTION;
INSERT INTO __DB__.flyway_schema_history SELECT * FROM `paldogotgan_member`.flyway_schema_history;
INSERT INTO __DB__.flyway_schema_history SELECT * FROM `paldogotgan_wallet`.flyway_schema_history;
INSERT INTO __DB__.flyway_schema_history SELECT * FROM `paldogotgan_order`.flyway_schema_history;
INSERT INTO __DB__.flyway_schema_history SELECT * FROM `paldogotgan_product`.flyway_schema_history;
INSERT INTO __DB__.flyway_schema_history SELECT * FROM `paldogotgan_delivery`.flyway_schema_history;
INSERT INTO __DB__.flyway_schema_history SELECT * FROM `paldogotgan_payout`.flyway_schema_history;
INSERT INTO __DB__.flyway_schema_history SELECT * FROM `paldogotgan_media`.flyway_schema_history;
COMMIT;

RENAME TABLE
    `paldogotgan_member`.`member` TO __DB__.`member`,
    `paldogotgan_member`.`refresh_token` TO __DB__.`refresh_token`,
    `paldogotgan_member`.`seller_application` TO __DB__.`seller_application`,
    `paldogotgan_member`.`seller_profile` TO __DB__.`seller_profile`,
    `paldogotgan_wallet`.`buyer_wallet` TO __DB__.`buyer_wallet`,
    `paldogotgan_wallet`.`buyer_wallet_ledger` TO __DB__.`buyer_wallet_ledger`,
    `paldogotgan_wallet`.`seller_wallet` TO __DB__.`seller_wallet`,
    `paldogotgan_wallet`.`seller_wallet_ledger` TO __DB__.`seller_wallet_ledger`,
    `paldogotgan_wallet`.`wallet_charge` TO __DB__.`wallet_charge`,
    `paldogotgan_wallet`.`wallet_order_payment` TO __DB__.`wallet_order_payment`,
    `paldogotgan_wallet`.`wallet_terms` TO __DB__.`wallet_terms`,
    `paldogotgan_wallet`.`wallet_terms_agreement` TO __DB__.`wallet_terms_agreement`,
    `paldogotgan_order`.`cart` TO __DB__.`cart`,
    `paldogotgan_order`.`cart_item` TO __DB__.`cart_item`,
    `paldogotgan_order`.`order_item` TO __DB__.`order_item`,
    `paldogotgan_order`.`order_seller_group` TO __DB__.`order_seller_group`,
    `paldogotgan_order`.`order_third_party_consent` TO __DB__.`order_third_party_consent`,
    `paldogotgan_order`.`orders` TO __DB__.`orders`,
    `paldogotgan_product`.`product` TO __DB__.`product`,
    `paldogotgan_product`.`product_image` TO __DB__.`product_image`,
    `paldogotgan_product`.`product_option` TO __DB__.`product_option`,
    `paldogotgan_product`.`product_review` TO __DB__.`product_review`,
    `paldogotgan_product`.`product_review_media` TO __DB__.`product_review_media`,
    `paldogotgan_product`.`product_stock_allocation` TO __DB__.`product_stock_allocation`,
    `paldogotgan_delivery`.`default_shipping_address` TO __DB__.`default_shipping_address`,
    `paldogotgan_delivery`.`region_group` TO __DB__.`region_group`,
    `paldogotgan_delivery`.`remote_area_postal_code` TO __DB__.`remote_area_postal_code`,
    `paldogotgan_delivery`.`remote_area_postal_code_history` TO __DB__.`remote_area_postal_code_history`,
    `paldogotgan_delivery`.`seller_shipping_profile` TO __DB__.`seller_shipping_profile`,
    `paldogotgan_delivery`.`shipment` TO __DB__.`shipment`,
    `paldogotgan_delivery`.`shipment_address_change_request` TO __DB__.`shipment_address_change_request`,
    `paldogotgan_delivery`.`shipment_item` TO __DB__.`shipment_item`,
    `paldogotgan_payout`.`BATCH_JOB_EXECUTION` TO __DB__.`BATCH_JOB_EXECUTION`,
    `paldogotgan_payout`.`BATCH_JOB_EXECUTION_CONTEXT` TO __DB__.`BATCH_JOB_EXECUTION_CONTEXT`,
    `paldogotgan_payout`.`BATCH_JOB_EXECUTION_PARAMS` TO __DB__.`BATCH_JOB_EXECUTION_PARAMS`,
    `paldogotgan_payout`.`BATCH_JOB_EXECUTION_SEQ` TO __DB__.`BATCH_JOB_EXECUTION_SEQ`,
    `paldogotgan_payout`.`BATCH_JOB_INSTANCE` TO __DB__.`BATCH_JOB_INSTANCE`,
    `paldogotgan_payout`.`BATCH_JOB_INSTANCE_SEQ` TO __DB__.`BATCH_JOB_INSTANCE_SEQ`,
    `paldogotgan_payout`.`BATCH_STEP_EXECUTION` TO __DB__.`BATCH_STEP_EXECUTION`,
    `paldogotgan_payout`.`BATCH_STEP_EXECUTION_CONTEXT` TO __DB__.`BATCH_STEP_EXECUTION_CONTEXT`,
    `paldogotgan_payout`.`BATCH_STEP_EXECUTION_SEQ` TO __DB__.`BATCH_STEP_EXECUTION_SEQ`,
    `paldogotgan_payout`.`payout` TO __DB__.`payout`,
    `paldogotgan_payout`.`payout_candidate_items` TO __DB__.`payout_candidate_items`,
    `paldogotgan_payout`.`payout_failure` TO __DB__.`payout_failure`,
    `paldogotgan_payout`.`payout_item` TO __DB__.`payout_item`,
    `paldogotgan_payout`.`payout_seller_draft` TO __DB__.`payout_seller_draft`,
    `paldogotgan_payout`.`payout_seller_draft_item` TO __DB__.`payout_seller_draft_item`,
    `paldogotgan_payout`.`payout_snapshot` TO __DB__.`payout_snapshot`,
    `paldogotgan_payout`.`payout_snapshot_item` TO __DB__.`payout_snapshot_item`,
    `paldogotgan_payout`.`payout_system_draft_item` TO __DB__.`payout_system_draft_item`,
    `paldogotgan_payout`.`payout_wallet_deduction` TO __DB__.`payout_wallet_deduction`,
    `paldogotgan_media`.`media` TO __DB__.`media`;

DROP TABLE IF EXISTS `paldogotgan_member`.flyway_schema_history;
DROP TABLE IF EXISTS `paldogotgan_wallet`.flyway_schema_history;
DROP TABLE IF EXISTS `paldogotgan_order`.flyway_schema_history;
DROP TABLE IF EXISTS `paldogotgan_product`.flyway_schema_history;
DROP TABLE IF EXISTS `paldogotgan_delivery`.flyway_schema_history;
DROP TABLE IF EXISTS `paldogotgan_payout`.flyway_schema_history;
DROP TABLE IF EXISTS `paldogotgan_media`.flyway_schema_history;
DROP TABLE IF EXISTS `paldogotgan_refund`.flyway_schema_history;
