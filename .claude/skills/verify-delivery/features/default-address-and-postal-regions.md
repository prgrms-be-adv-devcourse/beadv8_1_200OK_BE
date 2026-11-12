# 기본 배송지와 도서산간 지역 관리

구매자는 기본 배송지를 저장하고 다시 읽는다. 관리자는 도서산간 우편번호(지역 그룹, 추가 소요일)를 등록, 수정, 삭제, 조회한다. 두 기능 모두 shipment 시드가 필요 없다.

## Sub-features

- `default-save` 기본 배송지 저장(`PUT`, 덮어쓰기).
- `default-read` 기본 배송지 조회(`GET`).
- `region-list` 관리자 우편번호 목록(페이지, 마이그레이션이 넣은 데이터가 있다).
- `region-register` 우편번호 등록(201). 지역 그룹은 기존 코드여야 한다.
- `region-update`, `region-delete` 수정과 삭제. 이 지도에서 실행하지 않았다.
- `region-role` BUYER 가 관리자 API 를 부르면 403 이다.

## How to get to it (user POV)

- 구매자: `PUT /api/v1/shipments/addresses/default`, `GET /api/v1/shipments/addresses/default`.
- 관리자: `GET`, `POST /api/v1/admin/postal-regions`, `PUT`, `DELETE /api/v1/admin/postal-regions/{postalCode}`.

## Driving it with curl

Preconditions:

- `doctor.sh` 가 통과했다. 시드는 필요 없다.
- 지역 그룹 코드는 `ETC_ISLANDS, JEJU_ISLANDS, JEJU_MAIN, MOUNTAIN, ULLEUNG, WEST_SEA_ISLANDS` 다(`region_group` 테이블).

`A='{"recipientName":"김철수","recipientPhone":"0212345678","postalCode":"63100","address":"제주특별자치도 제주시 첨단로 1","addressDetail":"2층","deliveryNote":null}'`

- **기본 배송지 저장.** `$SKILL/scripts/call.sh default-address/put BUYER:100 PUT /api/v1/shipments/addresses/default "$A"`. 200, 요청과 같은 배송지.
- **기본 배송지 조회.** `$SKILL/scripts/call.sh default-address/get BUYER:100 GET /api/v1/shipments/addresses/default`. 200, 저장한 값.
- **DB.** `$SKILL/scripts/sql.sh default-address/db "SELECT member_id,postal_code,recipient_name FROM default_shipping_address"`. `member_id` 100 한 행.
- **목록.** `$SKILL/scripts/call.sh postal-region/list ADMIN:1 GET '/api/v1/admin/postal-regions?page=0'`. 200, `content[]` 에 `postalCode`, `regionGroup`, `extraDeliveryDays`.
- **등록.** `$SKILL/scripts/call.sh postal-region/register ADMIN:1 POST /api/v1/admin/postal-regions '{"postalCode":"99999","regionGroup":"JEJU_MAIN","extraDeliveryDays":1}'`. 201.
- **등록 DB.** `$SKILL/scripts/sql.sh postal-region/db "SELECT postal_code,region_group,extra_delivery_days,deleted_at FROM remote_area_postal_code WHERE postal_code='99999'"`. 한 행, `deleted_at` NULL. 이어서 `fee-quote` 에 `99999` 를 넣으면 `JEJU_MAIN` 이 나온다.
- **역할 없음.** `$SKILL/scripts/call.sh postal-region/buyer BUYER:100 GET /api/v1/admin/postal-regions`. 403.

## Gotchas

- 등록한 `99999` 는 이 인스턴스의 DB 에만 있다. 정리하면 사라진다.
- 같은 우편번호를 다시 등록하면 409(`POSTAL_REGION_DUPLICATED`), 없는 지역 그룹은 422 다. 두 경로는 실행하지 않았다.
- 기본 배송지는 회원당 한 건(`member_id` 유니크)이라 `PUT` 은 덮어쓴다.
- 삭제는 soft delete(`deleted_at`)다. 수정과 삭제는 실행하지 않았다.
