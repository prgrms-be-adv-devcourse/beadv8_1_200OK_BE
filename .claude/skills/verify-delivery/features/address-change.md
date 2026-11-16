# 배송지 변경

구매자가 출고 전(`PREPARING`) shipment 의 배송지를 바꿔 달라고 요청하고, 판매자가 승인하거나 거절한다. 승인하면 shipment 의 배송지, 지역 그룹, 추가 운임, 도착 예정일이 새 배송지 기준으로 바뀐다.

## Sub-features

- `change-request` 구매자 요청(201, `PENDING`, 추가 운임 안내 금액).
- `change-duplicate` 처리 대기 요청이 있으면 새 요청은 409 다.
- `change-approve` 판매자 승인(`APPROVED`). shipment 에 반영된다.
- `change-reject` 판매자 거절(`REJECT`). 이 지도에서 실행하지 않았다.
- `change-visible` 구매자 상세의 `latestAddressChange` 로 결과가 보인다.

## How to get to it (user POV)

- 구매자 요청: `POST /api/v1/shipments/{shipmentId}/address-change-requests` 본문은 배송지(`recipientName`, `recipientPhone`, `postalCode`, `address`, `addressDetail`, `deliveryNote`).
- 판매자 처리: `PATCH /api/v1/seller/shipments/{shipmentId}/address-change-requests/{requestId}` 본문 `{"decision":"APPROVE"}` 또는 `REJECT`.

## Driving it with curl

Preconditions:

- `doctor.sh` 가 통과했다.
- `seed-shipments.sh` 로 **검증용 시드**를 넣었다. shipment 1002(구매자 100, 판매자 10, `PREPARING`, 일반 지역)를 쓴다. 1001 을 출고한 뒤에는 그 shipment 로 할 수 없다.

배송지 본문(제주 63100)을 `A` 로 쓴다.
`A='{"recipientName":"김철수","recipientPhone":"0212345678","postalCode":"63100","address":"제주특별자치도 제주시 첨단로 1","addressDetail":"2층","deliveryNote":null}'`

- **요청.** `$SKILL/scripts/call.sh address-change/request BUYER:100 POST /api/v1/shipments/1002/address-change-requests "$A"`. 201. `requestId` 1, `status` `PENDING`, `additionalFeeNotice` 3000(제주 추가 운임).
- **요청 DB.** `$SKILL/scripts/sql.sh address-change/db-request "SELECT id,shipment_id,status,requested_postal_code,additional_fee_notice FROM shipment_address_change_request"`. 한 행, `PENDING`.
- **중복 요청.** 같은 본문을 다시 보낸다. `$SKILL/scripts/call.sh address-change/duplicate BUYER:100 POST /api/v1/shipments/1002/address-change-requests "$A"`. 409 (`처리 대기 중인 배송지 변경 요청이 있습니다.`).
- **승인.** `$SKILL/scripts/call.sh address-change/approve SELLER:10 PATCH /api/v1/seller/shipments/1002/address-change-requests/1 '{"decision":"APPROVE"}'`. 200. `status` `APPROVED`, `processedAt` 이 채워진다.
- **승인 뒤 shipment DB.** `$SKILL/scripts/sql.sh address-change/db-shipment "SELECT id,status,postal_code,region_group,region_surcharge,estimated_delivery_start FROM shipment WHERE id=1002"`. `postal_code` `63100`, `region_group` `JEJU_MAIN`, `region_surcharge` 3000, 도착 예정일 시작이 변경 전보다 늦다.
- **구매자 화면.** `$SKILL/scripts/call.sh address-change/buyer-detail BUYER:100 GET /api/v1/shipments/1002`. `latestAddressChange.status` `APPROVED`, `requestedAddress.postalCode` `63100`.
- **Proof.** 요청, 승인 응답과 두 번의 DB 행, 구매자 상세.

## Gotchas

- 이 기능은 **시드 shipment**에 대해서만 확인한다. 이벤트로 만든 shipment 와 같은 모양으로 맞췄지만 상류 경로는 아니다.
- 출고 후(`SHIPPING` 이상)에는 요청이 409(`ADDRESS_CHANGE_NOT_ALLOWED`)다. 이 지도에서 그 경로는 실행하지 않았다.
- `decision` 은 `APPROVE|REJECT` 문자열이고 소문자나 다른 값은 422 다. 거절 경로(`REJECT`)와 거절 사유는 실행하지 않았다.
- 배송이 중단된 지역(`REGION_SUSPENDED`)을 만드는 API 가 이 모듈에 없어 확인하지 않는다.
- 다른 구매자의 요청, 다른 판매자의 처리, 요청 본문 검증 실패는 실행하지 않았다.
