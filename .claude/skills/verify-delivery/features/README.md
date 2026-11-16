# delivery 검증 지도

이 폴더는 delivery 모듈의 사용자 접점(HTTP API)을 검증하는 레시피의 정본이다. 앱을 구동하기 전에 이 색인을 읽고, 해당 기능 파일을 레시피로 쓴다. 띄우는 법, 토큰, 시드, 증거는 [../SKILL.md](../SKILL.md) 에 있다.

## Baseline preconditions

- `scripts/launch.sh` 로 새 인스턴스를 띄운다. 앱은 `http://127.0.0.1:$APP_PORT`(빈 포트), MySQL 은 `127.0.0.1:$DB_PORT`(임의 포트)다. 택배사는 `mock`, 프로파일은 `dev`.
- `export VD_STATE=<launch.sh 가 출력한 state 경로>` 와 `SKILL=.claude/skills/verify-delivery`.
- `$SKILL/scripts/doctor.sh` 가 종료 코드 0 이어야 한다.
- shipment 가 필요한 기능(구매자 조회, 출고, 배송지 변경)은 `$SKILL/scripts/seed-shipments.sh` 로 **검증용 시드**를 넣는다. 구매자 100, 판매자 10, shipment 1001(조회, 출고용)과 1002(배송지 변경용), 모두 `PREPARING`, 지역 `GENERAL`.
- 이 스킬이 시작한 인스턴스만 드라이브한다. `localhost:8080`(사용자의 compose 앱)으로 요청이 가면 안 된다.

## Driving conventions

- 요청은 `scripts/call.sh <증거이름> <역할:회원ID|none> <METHOD> <경로> ['<JSON>']` 로 보낸다. 증거 이름은 `<기능>/<단계>` 로 짓는다.
- DB 확인은 `scripts/sql.sh <증거이름> "<SELECT>"` 로 한다. 스키마는 `paldogotgan_delivery`.
- 인증이 필요한 요청의 역할과 회원 ID 는 각 레시피가 정한다. 회원 ID 가 데이터 소유자 판단에 쓰인다(다른 회원은 404).
- 오류 응답은 RFC 9457 형식(`title`, `status`, `detail`, `instance`)이고, 검증 실패는 422 와 `errors[]`(`pointer`, `path`, `detail`)다.
- 한 번 상태를 바꾼 shipment 는 되돌리지 않는다. 같은 레시피를 다시 돌리려면 새 인스턴스를 띄운다.

## Proof and skip reporting

- 행동(요청)과 결과 상태(응답과 이어지는 조회)를 모두 남기고, 변경은 `sql.sh` 로 DB 행을 함께 확인한다.
- 사전 조건이 없으면 조작해서 통과시키지 말고 그 기능 파일의 `Gotchas` 에 이유를 쓴다.
- 이 지도에서 실행하지 않은 경로를 다른 경로로 확인한 것처럼 쓰지 않는다.

## Features

- [배송비 견적](./shipping-fee-quote.md): 공개 견적 `POST /api/v1/shipments/fee-quotes` 와 order 용 내부 `POST /internal/v1/delivery/shipping-fees`. shipment 시드가 필요 없다. 가장 단순해서 처음 증명할 때 쓴다.
- [구매자 배송 조회](./buyer-shipments.md): 목록과 상세, 본인 것만 보이는지, 권한. 시드가 필요하다.
- [판매자 출고와 배송 완료](./seller-dispatch.md): 판매자 목록, 상세, 출고 등록, Mock 택배사의 배송 완료(약 5초 뒤).
- [배송지 변경](./address-change.md): 구매자 요청, 판매자 승인, 추가 운임과 도착 예정일 재계산.
- [기본 배송지와 도서산간 지역 관리](./default-address-and-postal-regions.md): 구매자의 기본 배송지 저장과 조회, 관리자의 우편번호 등록과 조회.

## 실행 상태

- 증명 실행(launch, doctor, 구동, 증거, 정리를 처음부터 끝까지)에서 확인한 것: 배송비 견적, 내부 shipping-fees, 구매자 배송 조회, 판매자 출고와 Mock 택배사 배송 완료(약 5초).
- 같은 코드로 한 번 구동해 본 것: 배송지 변경(요청, 중복, 승인), 기본 배송지 저장과 조회, 관리자 우편번호 목록과 등록, 관리자 API 의 BUYER 403. 증명 실행에는 넣지 않았으므로 다음 실행에서 증거를 남기며 다시 확인한다.
- 각 파일의 Gotchas 에 "실행하지 않았다"고 쓴 경로는 확인하지 않은 것이다.
