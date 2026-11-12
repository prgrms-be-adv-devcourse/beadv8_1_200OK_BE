-- 검증용 시드. 상류(order 모듈)의 OrderPaidEvent 경계를 대신한다. 단독 실행하는 delivery 에는 order 가 없어서
-- 실제로는 이벤트가 만들 shipment 와 shipment_item 행을 직접 넣는다.
-- 컬럼 값은 delivery/src/test/java/com/ok/delivery/fixture/ShipmentFixture.java 의 shipment(...)/item(...) 과 같은 모양이다
-- (지역 GENERAL, 기본 배송비 3000, 부과 3000, 추가 운임 0, 무료배송 기준 50000, 상태 PREPARING).
-- 구매자 100, 판매자 10. 날짜는 실행일 기준으로 맞춘다(출고 예정일 <= 도착 예정일 시작 제약).
-- 사용: VD_STATE=<state> scripts/seed-shipments.sh
INSERT INTO paldogotgan_delivery.shipment
 (id, order_id, buyer_id, seller_id, status, carrier, tracking_number,
  recipient_name, recipient_phone, postal_code, address, address_detail, delivery_note,
  region_group, base_shipping_fee, charged_shipping_fee, region_surcharge, free_shipping_threshold,
  estimated_ship_date, estimated_delivery_start, estimated_delivery_end, is_delayed, created_at, updated_at)
VALUES
 (1001, 9001, 100, 10, 'PREPARING', NULL, NULL,
  '홍길동', '01012345678', '06236', '서울시 강남구 테헤란로 1', '101동 202호', '문 앞에 놓아주세요',
  'GENERAL', 3000, 3000, 0, 50000,
  CURDATE(), DATE_ADD(CURDATE(), INTERVAL 2 DAY), DATE_ADD(CURDATE(), INTERVAL 3 DAY), FALSE, NOW(6), NOW(6)),
 (1002, 9002, 100, 10, 'PREPARING', NULL, NULL,
  '홍길동', '01012345678', '06236', '서울시 강남구 테헤란로 1', '101동 202호', '문 앞에 놓아주세요',
  'GENERAL', 3000, 3000, 0, 50000,
  CURDATE(), DATE_ADD(CURDATE(), INTERVAL 2 DAY), DATE_ADD(CURDATE(), INTERVAL 3 DAY), FALSE, NOW(6), NOW(6));

INSERT INTO paldogotgan_delivery.shipment_item
 (id, shipment_id, order_item_id, product_name, option_name, quantity, unit_price, canceled_quantity, reserved_cancel_quantity, confirmed, created_at, updated_at)
VALUES
 (2001, 1001, 7001, '무선 키보드', '블랙', 2, 30000, 0, 0, FALSE, NOW(6), NOW(6)),
 (2002, 1001, 7002, '무선 키보드', '블랙', 2, 30000, 0, 0, FALSE, NOW(6), NOW(6)),
 (2003, 1002, 7003, '무선 키보드', '블랙', 2, 30000, 0, 0, FALSE, NOW(6), NOW(6));
