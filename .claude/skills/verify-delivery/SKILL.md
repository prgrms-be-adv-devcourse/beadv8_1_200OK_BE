---
name: verify-delivery
description: paldo-gotgan 의 delivery 모듈(헥사고날, 포트 8086 기본)을 단독으로 띄워 HTTP API(배송비 견적, 구매자 배송 조회, 판매자 출고와 Mock 택배사 배송 완료, 배송지 변경, 기본 배송지, 관리자 도서산간 관리, 내부 shipping-fees)를 curl 로 사용자처럼 구동하고 DB 행까지 증거로 남긴다. delivery 변경이 실제 앱에서 동작하는지 증명해야 할 때 쓴다. 다른 모듈용이 아니다.
---

# delivery 검증 (verify-delivery)

delivery 모듈을 `./gradlew :delivery:bootRun` 으로 단독 실행해 사용자 경로(HTTP)로 구동하고, 응답과 DB 행을 증거로 남긴다. 기존 `*ApiIntegrationTest`(Testcontainers), `DeliveryModuleTest`, `FakeCarrierClient` 는 테스트 하니스이고, 이 스킬은 실제 앱을 띄워 쓰는 쪽이다. 기능별 레시피는 [features/README.md](features/README.md) 를 먼저 읽는다.

## 표면과 격리 (먼저 읽는다)

- 사용자 접점은 HTTP API 와 이벤트다. 이 스킬은 HTTP 를 구동한다. 이벤트 중 출고 이벤트와 Mock 택배사 배송 완료는 API 로 출고하면 따라 일어나서 함께 확인된다. 주문의 `OrderPaidEvent` 는 단독 실행에 order 가 없어 발행하지 않는다(아래 "검증용 시드").
- delivery 는 Redis 와 Kafka 를 쓰지 않는다. `delivery/src/main` 에 연동 코드가 없고 `application-delivery.yaml` 이 `common-redis.yaml` 을 import 하지 않는다. 띄우는 것은 MySQL 하나다. (기동 로그에 Spring Data Redis 저장소 후보 탐색 INFO 가 나오는데 연결은 시도하지 않는다.)
- 이 스킬은 항상 새 인스턴스(임의 포트의 MySQL 컨테이너, 빈 포트의 앱)를 띄운다. 사용자의 `docker compose`(server 스택: 8080, 3306, 6379, kafka 등)와 포트와 네트워크를 공유하지 않는다. **그 스택과 `localhost:8080` 을 건드리지 않는다.** 이 스킬이 시작하지 않은 인스턴스는 드라이브하지 않는다.
- 작업 트리의 현재 상태(커밋 안 한 변경 포함)를 그대로 검증한다. 다른 작업의 미커밋 변경이 있으면 그것도 함께 올라간다. 필요하면 `git status` 로 무엇이 올라가는지 확인하고 보고에 적는다.

## 함정 두 가지

1. **앱은 실제 트리가 아니라 복사본에서 띄운다.** iCloud 동기화 폴더에서는 이름 끝이 ` 2` 인 사본 파일(`Foo 2.java` 등)이 생긴다. 이 파일이 있으면 `common` 컴파일이 깨진다. `launch.sh` 가 `rsync --exclude='* 2*'` 로 사본을 뺀 복사본을 `mktemp -d` 아래에 만들고 거기서 `bootRun` 한다. 실제 트리에서 `./gradlew :delivery:bootRun` 을 직접 돌리지 않는다.
2. **MySQL 은 `--default-time-zone=+09:00` 으로 띄운다.** 앱은 `Asia/Seoul` 로 읽고 쓴다. 컨테이너가 UTC 면 시드의 `NOW()`, `CURDATE()` 가 9시간 어긋나 출고 예정일, 생성 시각이 하루 전으로 보인다. (처음 시도에서 실제로 어긋났다.)

## Launch

```bash
SKILL=.claude/skills/verify-delivery        # 저장소 루트 기준
$SKILL/scripts/launch.sh                     # 약 1분. 마지막 줄이 state 경로
export VD_STATE=<출력된 state 경로>            # 이후 모든 스크립트가 이 인스턴스를 가리킨다
```

`launch.sh` 가 하는 일(빈 상태에서 마이그레이션까지):

1. `mktemp -d` 로 증거 폴더(`verify-delivery-evidence.*/<실행ID>/`)와 작업 복사본(`verify-delivery-work.*`)을 만든다.
2. `rsync` 로 복사본을 만든다. 실제 저장소의 `.env`, `keys/`, `delivery/.env` 는 제외한다(읽지 않는다). `data/`, `infra/terraform` 도 뺀다.
3. 복사본에서 JWT 키쌍을 만든다. `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/rs256-v1-private.pem` 과 공개키. `bootRun` 이 `OK_ROOT_DIR` 을 복사본 루트로 지정해 이 키를 읽는다. 공개키 파일이 없으면 앱이 기동하지 않는다.
4. `docker run -d --name vd-mysql-<실행ID> -p 127.0.0.1::3306 mysql:9.4 --default-time-zone=+09:00`. root 비밀번호는 일회용이다. 검증용이라 팀 규칙의 모듈 계정 대신 root 로 간단히 한다. 접속 가능(`SELECT 1`)과 init 완료 로그를 모두 확인한 뒤 `CREATE DATABASE paldogotgan_delivery`. 스키마는 앱이 만들지 않으므로(`createSchemas=false`) 이 단계가 필요하다.
5. `./gradlew --no-daemon :delivery:bootRun` 을 복사본에서 실행한다. 환경변수는 `DB_HOST=127.0.0.1 DB_PORT=<임의> DB_USERNAME=root DB_PASSWORD=<일회용> SERVER_PORT=<빈 포트> DELIVERY_CARRIER_TYPE=mock`. 프로파일을 안 주면 `dev`(Swagger 로그인 없이 열림)와 `delivery` 가 켜진다. `--no-daemon` 은 정리할 프로세스 트리를 우리 것으로 한정하려는 것이다.
6. 준비 신호는 `/actuator/health` 가 `UP` 이고 앱 로그에 `Started DeliveryApplication` 이 있는 것이다. Flyway 가 빈 스키마에 `V202610111500` 부터 최신 버전까지 적용한다.

택배사는 `mock` 이다. 출고하면 약 5초 뒤 배송 완료(DELIVERED)로 바뀐다. 실제 Delivery Tracker(`tracker`)는 시크릿이 필요해 쓰지 않는다.

## Doctor

```bash
$SKILL/scripts/doctor.sh      # 읽기 전용. 종료 코드 0 이면 드라이브할 가치가 있다
```

기록한 PID 가 살아 있는지, 포트를 연 프로세스가 우리 프로세스 트리인지, 8080 이 아닌지, MySQL 컨테이너가 우리 것인지, health `UP`, Flyway 최신 버전이 `db/migration/delivery` 의 마지막 파일과 같은지, BUYER 토큰으로 `GET /api/v1/shipments` 가 200 이고 토큰이 없으면 401 인지를 본다. 뭔가 이상하면 드라이브 전에 이것부터 돌린다.

## 토큰

로그인은 member 모듈 몫이라 단독 실행에는 없다. 복사본에서 만든 개인키로 토큰을 직접 발급한다.

```bash
$SKILL/scripts/mint-token.py --key "$WORK/keys/rs256-v1-private.pem" --sub 100 --roles BUYER
$SKILL/scripts/mint-token.py --key "$WORK/keys/rs256-v1-private.pem" --sub 10  --roles SELLER BUYER
$SKILL/scripts/mint-token.py --key "$WORK/keys/rs256-v1-private.pem" --sub 1   --roles ADMIN
$SKILL/scripts/mint-token.py --key ... --sub 100 --roles BUYER --expired      # 만료 토큰(401 확인용)
```

claim 모양은 `common` 의 `JwtConfig`, `TestAccessTokens` 와 같다. 헤더 `alg=RS256, typ=at+jwt, kid=rs256-v1`, 본문 `iss=paldo-gotgan`, `aud=["paldo-gotgan-api"]`, `sub=<회원 ID 숫자>`, `iat`, `exp`(없으면 거부), `roles=["BUYER"|"SELLER"|"ADMIN"]`. `typ`, `kid` 가 없거나 다르면 401 이다. `$WORK` 는 `source $VD_STATE` 로 얻는다.

권한은 컨트롤러의 `@PreAuthorize` 가 정한다. `/api/v1/shipments`(견적 포함)와 `/api/v1/shipments/addresses/default`, `/api/v1/shipments/{id}/address-change-requests` 는 BUYER, `/api/v1/seller/shipments/**` 는 SELLER, `/api/v1/admin/postal-regions` 는 ADMIN 이다. **견적 API 도 로그인이 필요하다**(공개 API 목록에 없다). 회원 ID 가 데이터 소유자 판단에 쓰이므로(`@CurrentMemberId`) 시드의 구매자 100, 판매자 10 과 맞춘다.

## 검증용 시드 (shipment)

shipment 는 production 에서 order 의 `OrderPaidEvent` 로만 만들어진다. 단독 실행에는 order 가 없고 API 로 만드는 길이 없다. 그래서 shipment 가 필요한 기능은 **검증용 시드**로 SQL 행을 넣는다. 상류(order)의 경계를 대신하는 것이고, 이벤트 처리(`OrderPaidEventListener`) 자체는 이 스킬이 검증하지 않는다.

```bash
$SKILL/scripts/seed-shipments.sh   # shipment 1001, 1002 (구매자 100, 판매자 10, GENERAL, PREPARING) 와 항목 3개
```

[scripts/seed-shipments.sql](scripts/seed-shipments.sql) 의 컬럼은 `ShipmentFixture.shipment(...)` 와 마이그레이션 SQL(`V202610111500` + 이후 ALTER) 로 맞췄다. 컬럼이 추가되면 이 SQL 도 고친다(`NOT NULL` 컬럼이 빠지면 INSERT 가 실패해서 알 수 있다). 1001 은 조회, 출고, 배송 완료에, 1002 는 배송지 변경에 쓴다. 한 번 쓰면 상태가 바뀌므로 다시 쓰려면 새 인스턴스를 띄운다.

## Drive

요청은 `call.sh` 로 보낸다. 요청, 응답 본문, HTTP 상태를 증거 폴더에 저장하고 화면에도 보여 준다.

```bash
$SKILL/scripts/call.sh <증거이름> <역할[,역할]:회원ID|none> <METHOD> <경로> ['<JSON>']
$SKILL/scripts/call.sh buyer-shipment/list BUYER:100 GET /api/v1/shipments
$SKILL/scripts/sql.sh  buyer-shipment/db "SELECT id,status FROM shipment WHERE id=1001"
```

엔드포인트와 본문은 기능 파일에 있다. 요청은 항상 `http://127.0.0.1:$APP_PORT` 로 간다. 8080 으로 가면 잘못된 것이다.

## Evidence

- 위치는 `$EVID` (= `mktemp -d` 아래 `verify-delivery-evidence.*/<실행ID>/`)다. `launch.sh` 가 만들고, 정리가 지우지 않는다. 증명을 끝내면 이 경로를 사용자에게 출력한다.
- 남기는 것: `<이름>.req.txt`(메서드, 경로, 역할, 본문), `<이름>.resp.json`, `<이름>.status.txt`, `<이름>.sql.txt`(SELECT 결과), `app.log`(앱 로그 전체).
- 증명 기준: 사용자 경로(HTTP)로 구동한다. 행동(요청)과 결과 상태(응답, 그리고 이어지는 조회)를 모두 남긴다. 부수효과(DB 행)를 `sql.sh` 로 함께 확인한다. 내부 setter 나 테스트 전용 엔드포인트를 쓰지 않는다. 예외는 위의 shipment 시드 하나뿐이고, 증거에 "시드" 라고 표시한다.
- 앱은 INFO 에서 요청과 출고 처리를 한 줄씩 로그로 남기지 않는다. 로그로 확인할 수 있는 것은 기동(`Migrating schema`, `Started DeliveryApplication`)과 `WARN`, `ERROR` 뿐이다. 요청 처리 증거는 응답과 DB 행이 맡는다. 필요하면 `grep -E "Started DeliveryApplication|Migrating schema|WARN|ERROR" "$EVID/app.log"` 를 발췌해 남긴다.

## Cleanup

```bash
$SKILL/scripts/cleanup.sh     # VD_STATE 가 가리키는 인스턴스만
```

기록한 앱 PID 의 프로세스 트리, 컨테이너 ID, 작업 복사본(`verify-delivery-work.*`)만 정리한다. 프로세스 이름으로 kill 하지 않는다. 증거 폴더는 지우지 않는다. `launch.sh` 가 중간에 실패해도 스스로 `cleanup.sh` 를 호출한다. 실패한 시도 뒤에도 정리가 돌았는지 `docker ps` 와 `lsof -nP -iTCP:<포트>` 로 확인한다.

## Helpers

| 스크립트 | 용도 | 호출 |
|---|---|---|
| `scripts/launch.sh` | 격리 MySQL, 키, 복사본, 앱을 띄운다 | `launch.sh` |
| `scripts/doctor.sh` | 읽기 전용 점검 | `VD_STATE=… doctor.sh` |
| `scripts/mint-token.py` | BUYER, SELLER, ADMIN 토큰 발급 | 위 "토큰" |
| `scripts/call.sh` | 인증 curl 과 증거 저장 | 위 "Drive" |
| `scripts/sql.sh` | DB 행 확인과 증거 저장 | 위 "Drive" |
| `scripts/seed-shipments.sh` (+ `.sql`) | 검증용 shipment 시드 | 위 "검증용 시드" |
| `scripts/cleanup.sh` | 시작한 것만 정리 | `VD_STATE=… cleanup.sh` |
| `scripts/_lib.sh` | 다른 스크립트가 source 하는 공용 코드 | 직접 호출하지 않는다 |

## 확인하지 않은 것

- 실제 Delivery Tracker 택배사(`delivery.carrier.type=tracker`). 시크릿이 필요해 쓰지 않는다.
- `OrderPaidEvent` 로 shipment 가 만들어지는 경로. order 가 없어 시드로 대신한다.
- `monolith` 로 올렸을 때의 동작, nginx 를 거친 경로. 이 스킬은 모듈 단독 실행만 다룬다.
- 운영(prod) 프로파일. `dev` 로만 띄운다.
