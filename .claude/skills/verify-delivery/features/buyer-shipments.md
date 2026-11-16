# 구매자 배송 조회

구매자가 자기 주문의 배송 목록(최신순, 한 페이지 25건)과 상세(배송지, 상품 항목, 예정일, 최근 배송지 변경 요청)를 본다. 남의 shipment 는 보이지 않는다.

## Sub-features

- `buyer-list` 내 shipment 목록. 페이지 번호는 0부터.
- `buyer-detail` shipment 상세. 배송지, 항목, 예정일, 부과 배송비, `latestAddressChange`.
- `buyer-isolation` 다른 구매자의 shipment 는 404 다.
- `buyer-role` BUYER 역할이 없으면 403, 토큰이 없으면 401 이다.

## How to get to it (user POV)

- 내 배송 목록: `GET /api/v1/shipments?page=0`.
- 배송 상세: `GET /api/v1/shipments/{shipmentId}`.

## Driving it with curl

Preconditions:

- `doctor.sh` 가 통과했다.
- `seed-shipments.sh` 로 **검증용 시드**를 넣었다(구매자 100 의 shipment 1001, 1002). 시드가 없으면 목록은 빈 `content` 이고 상세는 404 라서 이 레시피는 실패한다. 조작해서 통과시키지 않는다.

- **목록.** `$SKILL/scripts/call.sh buyer-shipments/list BUYER:100 GET /api/v1/shipments`. 200. `content` 에 `shipmentId` 1002, 1001 이 최신순으로 있고 `status` 는 `PREPARING`, `size` 25, `totalElements` 2. 1001 은 항목이 2개라 `otherItemCount` 1, `representativeProductName` 은 `무선 키보드`.
- **상세.** `$SKILL/scripts/call.sh buyer-shipments/detail BUYER:100 GET /api/v1/shipments/1001`. 200. `orderId` 9001, `regionGroup` `GENERAL`, `chargedShippingFee` 3000, `address.postalCode` `06236`, `items` 2개(`orderItemId` 7001, 7002), `latestAddressChange` null.
- **다른 구매자.** `$SKILL/scripts/call.sh buyer-shipments/other-buyer BUYER:101 GET /api/v1/shipments/1001`. 404 (`배송 정보를 찾을 수 없습니다.`). 403 이 아니라 404 로 존재를 숨긴다.
- **역할 없음.** `$SKILL/scripts/call.sh buyer-shipments/seller-role SELLER:10 GET /api/v1/shipments`. 403. 토큰이 없으면 401.
- **Proof.** `$SKILL/scripts/sql.sh buyer-shipments/db "SELECT s.id, s.buyer_id, s.status, COUNT(i.id) AS items FROM shipment s JOIN shipment_item i ON i.shipment_id=s.id GROUP BY s.id"` 의 행(1001, 구매자 100, 항목 2)이 상세 응답과 같다.

## Gotchas

- 이 기능은 **시드 행**(상류 order 를 대신하는 SQL)을 조회한다. 이벤트로 만든 shipment 조회가 아니다.
- 구매자 ID 는 토큰의 `sub` 다. `BUYER:100` 이 아닌 회원은 시드를 볼 수 없다.
- `estimatedShipDate` 등 날짜는 시드를 넣은 날 기준이다(MySQL 컨테이너 시간대가 `+09:00` 이어야 앱과 일치한다. `launch.sh` 가 맞춘다).
- 음수 `page`(`page=-1`)는 422 다. 페이지 크기는 받지 않는다(25 고정).
