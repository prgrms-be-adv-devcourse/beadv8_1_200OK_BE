# 배송비 견적

구매자(주문 전 화면)가 배송지 우편번호와 판매자별 상품 금액을 보내면 판매자별 배송비, 도서산간 추가 운임, 출고 예정일, 도착 예정일 범위와 합계를 돌려받는다. 저장하지 않는 계산이다. order 모듈은 같은 계산을 내부 API 로 호출한다.

## Sub-features

- `quote-general` 일반 지역 우편번호의 견적. 무료배송 기준 이상이면 기본 배송비가 0 이다.
- `quote-remote` 도서산간 우편번호의 견적. 추가 운임이 붙고 도착 예정일이 늦어진다.
- `quote-validation` 잘못된 본문은 422 와 필드별 `errors[]` 를 돌려준다.
- `quote-auth` 토큰이 없으면 401 이다.
- `internal-shipping-fees` order 용 내부 API. 판매자 설정 값과 무료배송 적용 후 값을 돌려준다.

## How to get to it (user POV)

- 주문 전 배송비 확인: `POST /api/v1/shipments/fee-quotes` (로그인한 회원, 역할 제한 없음).
- 주문 생성 중 order 가 호출: `POST /internal/v1/delivery/shipping-fees` (같은 호스트의 loopback 에서만).

## Driving it with curl

Preconditions:

- `doctor.sh` 가 통과했다. shipment 시드는 필요 없다.
- 판매자 설정이 없는 판매자 10, 11 은 기본 설정(기본 배송비 3000, 무료배송 기준 50000, 마감 13:00)을 쓴다.
- 도서산간 우편번호 63100(`JEJU_MAIN`, 추가 운임 3000)은 마이그레이션이 넣은 데이터다.

- **일반 지역, 한 판매자는 무료.** `$SKILL/scripts/call.sh fee-quote/general BUYER:100 POST /api/v1/shipments/fee-quotes '{"postalCode":"06236","sellers":[{"sellerId":10,"itemAmount":45000},{"sellerId":11,"itemAmount":60000}]}'`. 200. `regionGroup` 은 `GENERAL`. 판매자 10 은 `shippingFee` 3000, 판매자 11 은 60000 원이 기준 50000 이상이라 `baseShippingFee` 0, `shippingFee` 0. `totalShippingFee` 는 3000. `expectedShipDate`, `arrivalStartDate`, `arrivalEndDate` 가 채워진다.
- **도서산간.** `$SKILL/scripts/call.sh fee-quote/jeju BUYER:100 POST /api/v1/shipments/fee-quotes '{"postalCode":"63100","sellers":[{"sellerId":10,"itemAmount":45000}]}'`. 200. `regionGroup` 은 `JEJU_MAIN`, `baseShippingFee` 3000, `regionSurcharge` 3000, `shippingFee` 6000. 도착 예정일이 일반 지역보다 하루 늦다.
- **검증 실패.** `$SKILL/scripts/call.sh fee-quote/invalid BUYER:100 POST /api/v1/shipments/fee-quotes '{"postalCode":"123","sellers":[]}'`. 422. `errors[]` 에 `/sellers` 와 `/postalCode` 가 있다.
- **토큰 없음.** `$SKILL/scripts/call.sh fee-quote/anonymous none POST /api/v1/shipments/fee-quotes '{"postalCode":"06236","sellers":[{"sellerId":10,"itemAmount":1000}]}'`. 401.
- **내부 API.** `$SKILL/scripts/call.sh internal/shipping-fees none POST /internal/v1/delivery/shipping-fees '{"postalCode":"63100","sellers":[{"sellerId":10,"itemAmount":45000}]}'`. 200. 응답 `sellers[0]` 은 `baseShippingFee` 3000(판매자 설정 값), `freeShippingThreshold` 50000, `shippingFee` 3000(무료배송 적용 후 기본 배송비), `regionSurcharge` 3000. 토큰이 필요 없다. 호스트에서 `127.0.0.1` 로 부르므로 loopback 으로 통과한다.
- **Proof.** 위 응답 본문과 상태가 `$EVID/fee-quote/`, `$EVID/internal/` 에 남는다. 저장하지 않는 계산이라 DB 부수효과는 없다. `sql.sh fee-quote/no-side-effect "SELECT COUNT(*) AS shipments FROM shipment"` 가 시드 전이면 0 인 것으로 확인한다.

## Gotchas

- **견적 API 도 로그인이 필요하다.** 공개 API 목록(`SecurityConfig.PUBLIC_ENDPOINTS`)에 없어서 토큰 없이는 401 이다. 역할은 아무거나 되지만 BUYER 로 부른다.
- 내부 API 의 `shippingFee` 는 추가 운임을 뺀 값이다. 공개 견적의 `shippingFee`(6000)와 뜻이 다르다. 내부 응답은 `baseShippingFee`(설정 값)와 `shippingFee`(적용 후)도 서로 다르다. 값을 섞어 비교하지 않는다.
- 내부 API 는 loopback 에서 온 요청만 받는다. 이 스킬의 호스트 curl 은 `127.0.0.1` 로 가므로 통과한다. 다른 주소(컨테이너 네트워크, nginx 경유)에서 오면 거부되는 것이 정상이고, 그 거부는 이 스킬에서 확인하지 않았다(호스트에서 비 loopback 주소로 보내는 경로가 없다). 단독 실행에서는 `@Profile` 제한이 없다.
- 날짜는 앱 시계(Asia/Seoul)와 마감 시각(기본 13:00)에 따라 달라진다. 출고 예정일, 도착 예정일은 실행한 시각에 따라 값이 다르므로 값이 아니라 형식과 "도서산간이 하루 늦다" 같은 관계로 확인한다.
- `sellers` 에 같은 `sellerId` 를 두 번 넣으면 422 다(`isSellerIdsUnique`). 판매자는 100명 이하.
- 판매자 설정(`seller_shipping_profile`)을 만드는 API 가 이 모듈에는 없다. 다른 값을 확인하려면 사용자 경로가 아닌 SQL 시드가 필요하므로, 이 레시피는 기본 설정 경로만 다룬다.
