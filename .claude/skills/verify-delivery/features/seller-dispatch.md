# 판매자 출고와 배송 완료

판매자가 자기 shipment 목록과 상세를 보고, 택배사와 송장번호를 등록해 출고한다. 출고하면 상태가 `SHIPPING` 이 되고, 택배사 추적 요청을 거쳐 Mock 택배사가 약 5초 뒤 배송 완료를 알려 `DELIVERED` 가 된다.

## Sub-features

- `seller-list` 내 shipment 목록(구매자 수령인, 지역, 출고 예정일, `addressChangePending`).
- `seller-detail` 상세. 구매자 상세와 같은 모양이다.
- `dispatch` 출고 등록(`POST .../dispatch`). `PREPARING` 에서 `SHIPPING` 으로.
- `dispatch-validation` 송장번호 형식(숫자 10~13자리)과 택배사 값 검증.
- `dispatch-guard` 남의 shipment 는 404, 이미 출고된 shipment 는 409, 구매자 역할은 403.
- `mock-delivered` 출고 약 5초 뒤 `DELIVERED` 와 `delivered_at` 이 채워진다.

## How to get to it (user POV)

- 판매자 목록: `GET /api/v1/seller/shipments?page=0`.
- 판매자 상세: `GET /api/v1/seller/shipments/{shipmentId}`.
- 출고: `POST /api/v1/seller/shipments/{shipmentId}/dispatch` 본문 `{"carrier":"CJ_LOGISTICS","trackingNumber":"1234567890"}`. 택배사는 `CJ_LOGISTICS, POST_OFFICE, HANJIN, LOTTE, LOGEN`.

## Driving it with curl

Preconditions:

- `doctor.sh` 가 통과했다.
- `seed-shipments.sh` 로 **검증용 시드**를 넣었다. shipment 1001 이 `PREPARING`(판매자 10)이어야 한다. 이미 출고한 인스턴스에서는 다시 할 수 없다.

- **목록.** `$SKILL/scripts/call.sh seller-dispatch/list SELLER:10 GET /api/v1/seller/shipments`. 200. 1001, 1002 가 `PREPARING`, `addressChangePending` false.
- **형식 오류.** `$SKILL/scripts/call.sh seller-dispatch/bad-tracking SELLER:10 POST /api/v1/seller/shipments/1001/dispatch '{"carrier":"CJ_LOGISTICS","trackingNumber":"123"}'`. 422. 상태는 그대로 `PREPARING`.
- **남의 판매자.** `$SKILL/scripts/call.sh seller-dispatch/other-seller SELLER:99 POST /api/v1/seller/shipments/1001/dispatch '{"carrier":"CJ_LOGISTICS","trackingNumber":"1234567890"}'`. 404.
- **구매자 역할.** `$SKILL/scripts/call.sh seller-dispatch/buyer-role BUYER:100 POST /api/v1/seller/shipments/1001/dispatch '{"carrier":"CJ_LOGISTICS","trackingNumber":"1234567890"}'`. 403.
- **출고.** `$SKILL/scripts/call.sh seller-dispatch/ok SELLER:10 POST /api/v1/seller/shipments/1001/dispatch '{"carrier":"CJ_LOGISTICS","trackingNumber":"1234567890"}'`. 200. `status` `SHIPPING`, `carrier` `CJ_LOGISTICS`, `trackingNumber` `1234567890`, `shippedAt` 가 채워진다.
- **출고 직후 DB.** `$SKILL/scripts/sql.sh seller-dispatch/db-after-dispatch "SELECT id,status,carrier,tracking_number,shipped_at,delivered_at FROM shipment WHERE id=1001"`. `SHIPPING`, `delivered_at` NULL.
- **배송 완료 대기.** 약 8초 뒤 같은 SELECT 를 다시 한다. `$SKILL/scripts/sql.sh seller-dispatch/db-after-delivered "SELECT ..."`. `DELIVERED`, `delivered_at` 이 `shipped_at` 약 5초 뒤. 구매자 상세(`BUYER:100 GET /api/v1/shipments/1001`)도 `DELIVERED`.
- **재출고.** `$SKILL/scripts/call.sh seller-dispatch/again SELLER:10 POST /api/v1/seller/shipments/1001/dispatch '{"carrier":"CJ_LOGISTICS","trackingNumber":"1234567891"}'`. 409.
- **Proof.** 요청과 응답, 두 시점의 DB 행(`shipped_at` 과 `delivered_at` 차이가 약 5초), 구매자 상세의 `DELIVERED`. 앱 로그에는 출고 처리 줄이 없다.

## Gotchas

- 배송 완료는 **Mock 택배사**가 보낸 알림이다(`MockCarrierClient`, 지연 5초, 메모리에서만 대기). 실제 택배사 연동은 확인하지 않는다. 대기 중에 앱을 재시작하면 알림이 사라지지만 기동 시 `SHIPPING` shipment 를 다시 요청해 복구한다(이 복구는 이 스킬이 확인하지 않았다).
- `DELIVERED` 이후 구매확정과 `COMPLETED` 전이는 order 의 구매확정 이벤트가 필요하다. 단독 실행으로는 만들 수 없어서 확인하지 않는다.
- 송장번호는 같은 택배사 안에서 중복될 수 없다(409 `TRACKING_NUMBER_DUPLICATED`). 같은 인스턴스에서 두 shipment 에 같은 번호를 쓰지 않는다.
- shipment 는 시드이므로 "출고 이벤트가 주문 흐름과 이어지는지"는 이 레시피 범위 밖이다.
