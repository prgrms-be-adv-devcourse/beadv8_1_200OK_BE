# 주문 흐름 추적·장애 감지 로깅 시스템 설계

- 에픽: #375
- 하위 이슈: #376 ~ #384
- 관련 이슈: #374 (개발 모니터링)
- 결정 이유와 버린 대안: [`docs/decisions/common/375-order-flow-tracking.md`](../decisions/common/375-order-flow-tracking.md)
- 기준일: 2026-11-05. 구현하면서 바뀐 내용은 이 문서도 같이 고친다. 코드와 이 문서가 다르면 코드가 기준이다.

이 문서는 시스템 전체가 **무엇을, 어떻게** 하는지를 한곳에 모은다. **왜** 그렇게 정했는지는 위의 ADR에 있다.

## 1. 목적

> 분산 환경에서 코드의 직접 변경을 최소화하여 데이터의 흐름을 파악하고, 성공 데이터보다 실패 데이터를 놓치지 않고 관리하여 빠르게 장애를 파악하고 대응하는 시스템이다.

이 정의에서 나온 설계 원칙은 다섯 가지다.

| 원칙 | 의미 |
|---|---|
| 코드 변경 최소화 | 모듈 개발자는 추적 어노테이션만 붙인다. 나머지는 경계 지점에서 자동으로 수집한다 |
| 실패 우선 | 실패 로그는 동기로 쓰고 버리지 않는다. 성공 로그는 큐가 차면 버려도 된다 |
| 비즈니스 무영향 | 로그 스택이나 flowwatch 모듈이 멈춰도 주문 처리는 계속된다 |
| 상태의 진실 원천은 엔티티 | 로그는 append-only 기록이다. 로그를 보고 주문 상태를 판단하지 않는다 |
| 하나의 키로 묶기 | 진입점이 달라도 orderId(+shipmentId)로 한 주문의 흐름을 모두 조회한다 |

## 2. 범위

**포함**
- 주문 생성부터 정산까지 한 주문의 흐름을 기록하고 조회한다.
- 실패(비즈니스 실패, 시스템 예외, 외부 호출 실패)를 기록한다.
- 다음 단계가 오지 않아 멈춘 주문을 감지하고 알린다.

**범위 밖**
- 고객·CS용 배송 조회 화면
- 배송사 실제 연동 구현. 계약 기준 설계만 남긴다(§10)
- Kafka 전환 자체. 이슈는 아직 만들지 않았다
- 운영 서버 지표(Prometheus) 수집. 맡는 이슈가 아직 없다. 개발 환경 지표는 #374에서 다룬다

## 3. 대상 흐름

한 주문은 아래 단계를 거친다. 진입점이 HTTP, 스케줄러, 배치, 외부 콜백으로 서로 달라서 traceId는 중간에 바뀔 수 있다. 흐름을 묶는 키는 orderId다.

| 단계(step) | 모듈 | 진입점 | 다음 단계까지 기준 시간 |
|---|---|---|---|
| 주문 생성 (결제 대기) | order | HTTP | 결제까지 5분 |
| 지갑 결제 (잔액 확인·차감) | wallet | 이벤트 | 주문 결제 반영까지 5분 |
| 재고 할당 | product | 내부 HTTP | (주문 생성과 같은 요청) |
| 배송비 조회 | delivery | 내부 HTTP | (주문 생성과 같은 요청) |
| 주문 결제 완료 | order | 이벤트 | 배송 생성 영수증까지 5분 |
| 배송 생성 (준비 중) | delivery | 이벤트 | 발송까지 3일 |
| 발송 (배송 중) | delivery | HTTP(판매자) | 배송 완료까지 2일 |
| 배송 완료 | delivery | 외부 배송사 콜백 | 구매 확정까지 3일 1시간 |
| 구매 확정 | order | HTTP(구매자) / `PurchaseConfirmScheduler` | 정산 영수증까지 5분 |
| 정산 | payout | Spring Batch (`PayoutJobScheduler`) | 끝 |

- 한 주문에 판매자별로 배송이 여러 건 생기므로, 배송 단계부터는 shipmentId로도 구분한다.
- 기준 시간은 flowwatch의 흐름 정의 yaml에서 바꾼다(§8.3).

## 4. 전체 구조

```mermaid
flowchart LR
  subgraph APP["앱 EC2 (monolith)"]
    direction TB
    IN["진입점<br/>HTTP 필터 · 스케줄러 · 배치 리스너"] --> CTX["TraceContext (MDC)<br/>traceId · orderId · shipmentId"]
    CTX --> CAP["수집 지점<br/>예외 경계 · HTTP 클라이언트<br/>PostCommit 리스너 · @Modifying Aspect<br/>Kafka 인터셉터 · 컨슈머 래퍼"]
    CAP --> ENC["JSON 인코더<br/>마스킹 · 크기 상한 · 억제"]
    ENC -->|성공: 비동기, 넘치면 버림| FILE[(로컬 로그 파일)]
    ENC -->|실패: 동기| FILE
    FILE --> ALLOY["Alloy 에이전트"]
  end
  ALLOY --> LOKI["Loki 단일 바이너리<br/>(로그 EC2)"]
  LOKI --> S3[(S3)]
  LOKI --> GRAFANA["Grafana"]

  subgraph KAFKA["Kafka"]
    BIZ["주문 흐름 토픽"]
    RCPT["flowwatch.receipt"]
  end
  CAP -.영수증 fire-and-forget.-> RCPT
  BIZ --> TRK["flowwatch 모듈<br/>group: flowwatch-observer"]
  RCPT --> TRK
  TRK --> FLOW[(order_flow)]
  TRK --> DET["StuckDetector"] --> SLACK["Slack"]
```

경로는 두 개다.
- **로그 경로**: 무슨 일이 일어났는지 남기고 조회한다. 1단계(#376~#381)에서 만든다.
- **감지 경로**: 일어나야 할 일이 일어나지 않았는지 찾는다. 2단계(#382~#384)에서 만들고, 주문 흐름 이벤트를 Kafka로 옮긴 뒤에 시작한다.

## 5. 추적 컨텍스트 (#376, #377)

### 5.1 키

| 키 | 발급과 출처 | 용도 |
|---|---|---|
| `traceId` | Snowflake. 진입점마다 새로 발급하거나 헤더에서 이어받는다 | 한 요청이나 한 번의 실행 안의 로그를 잇는다 |
| `orderId` | 주문 생성 시점에 bind하거나 헤더, `@TraceKey`에서 얻는다 | 주문 흐름 전체를 묶는다 (**주 키**) |
| `shipmentId` | 배송 생성 이후 | 판매자별 배송을 구분한다 |
| `module` | 실행 중인 모듈 | 필터 |
| `step` | 흐름 단계 | 흐름 위치 |
| `eventType` | 수집 지점 | 로그 종류 (§6.2) |

### 5.2 발급 지점

| 진입점 | 동작 |
|---|---|
| HTTP | `TraceFilter`: 서버끼리 부르는 `/internal/` 요청(loopback에서 온 것)은 `X-Trace-Id`를 이어받고, 그 밖의 요청은 항상 새로 만든다. 응답 헤더로 돌려준다 |
| `@Scheduled` | 실행할 때마다 새 traceId를 발급한다 |
| Spring Batch | `JobExecutionListener`로 실행 단위마다 새 traceId를 발급한다 |
| Kafka 소비 | 메시지 헤더에서 복원한다 (#382) |
| 외부 콜백 | 새로 발급한다. orderId는 송장 번호로 Shipment를 찾아서 bind한다 |

Snowflake 노드 ID는 `trace.node-id` 설정으로 정한다. 지금은 인스턴스가 하나라고 가정한다.

구현(#376, `com.ok.common.trace`)은 다음과 같다.
- **traceId**: 41비트 시각(기준 2026-01-01T00:00:00Z) + 10비트 노드 ID + 12비트 시퀀스. 시각은 공용 `Clock` 빈에서 읽는다. 값에서 발급 시각을 역산할 수 있다 (`docs/DEVELOPMENT.md` 12절).
- **HTTP**: `TraceFilter`가 Spring Security 필터보다 앞(order -200)에 있어 401·403 응답에도 `X-Trace-Id` 헤더가 실린다. 요청 헤더는 `/internal/`이면서 loopback에서 온 요청에서만, 그중에서도 0으로 시작하지 않는 숫자 15~18자리일 때만 이어받고, 아니면 무시하고 새로 발급한다. 외부 호출자가 이전 요청의 traceId를 보내 `eventKey`(traceId가 구분자)와 겹치게 만드는 것을 막기 위해서다.
- **`@Scheduled`**: Boot가 만드는 `ObservationRegistry`에 `ObservationHandler`를 등록해, 작업을 실행하는 스레드에서 범위가 열릴 때 새 traceId를 넣고 닫힐 때 이전 MDC로 되돌린다.
- **Spring Batch**: `BeanPostProcessor`가 모든 `AbstractJob`에 `JobExecutionListener`를 붙인다. Batch가 클래스패스에 없는 앱(order 등)에서는 등록되지 않는다.
- 이 이슈에서 만든 것은 발급과 MDC 설정까지다. `module` 값 채우기, `HTTP_IN` 로그는 만들지 않았다.

### 5.3 전파

| 경계 | 방식 | 이슈 |
|---|---|---|
| Modulith 비동기 리스너 | 기존 `MdcTaskDecorator`가 모든 비동기 실행기에서 MDC를 복사한다 (Kafka로 옮기기 전까지). 같은 일을 하는 `TraceTaskDecorator`를 따로 만들지 않았다 | #377 |
| 내부 HTTP (`InternalRestClient`) | `TraceHeaderInterceptor`가 MDC에 값이 있는 `X-Trace-Id`, `X-Order-Id`, `X-Shipment-Id`만 헤더로 보내고, `TraceFilter`가 `/internal/` 경로에서 복원한다 | #377 |
| Kafka | producer 인터셉터가 헤더를 넣고 consumer가 복원한다 | #382 |

내부 HTTP 헤더가 빠지면 받는 쪽의 bulk 쿼리(재고 할당)가 어느 주문인지 알 수 없다.

구현(#377)은 다음과 같다.
- **비동기 리스너**: `AsyncConfig`가 모든 실행기에 `MdcTaskDecorator`를 적용한다. 리스너를 띄우는 스레드의 MDC 전체(traceId, orderId 등)를 그대로 복사하고, 끝나면 실행 스레드의 MDC로 되돌린다.
- **보내는 쪽**: `TraceHeaderInterceptor`를 주문·환불의 `InternalRestClient`와 상품의 주문 조회 클라이언트(`OrderApiClient`)에 한 줄씩 붙였다. 모듈의 호출 코드는 바꾸지 않는다.
- **받는 쪽**: 별도 필터를 두지 않고 `TraceFilter`가 복원한다. `X-Trace-Id`, `X-Order-Id`, `X-Shipment-Id`는 모두 `/internal/` 경로이면서 loopback 주소에서 온 요청에서만 이어받는다(Security의 `/internal/**` 조건과 같다. 필터가 Security보다 앞이라 막힐 요청의 헤더도 먼저 읽기 때문이다). 공개 API에서 받으면 호출자가 임의의 주문 번호로 로그를 오염시킬 수 있기 때문이다. 형식(0으로 시작하지 않는 숫자 1~18자리)이 틀리면 무시하고, 요청이 끝나면 진입 전 MDC로 되돌린다.
- 이어지지 않는 곳: 미완료 이벤트 재발행(호출 스레드가 없다)과 Kafka(#382)는 전파되지 않는다.

## 6. 수집

### 6.1 수집 지점

| 수집 지점 | 기록 대상 | eventType | 이슈 |
|---|---|---|---|
| HTTP 필터 | 요청과 응답 요약 (성공은 10%만 샘플링) | `HTTP_IN` | #378 (#376의 `TraceFilter`는 traceId만 다룬다) |
| `RestApiException` 처리 | 잔액 부족, 재고 부족 같은 비즈니스 실패와 에러 코드 | `BUSINESS_FAILURE` | #379 |
| `ApiControllerAdvice` | 처리되지 않은 예외 (1회만) | `SYSTEM_FAILURE` | #379 |
| 비동기 리스너 | 리스너 처리 실패 | `SYSTEM_FAILURE` | #379 |
| HTTP 클라이언트 | 내부·외부 호출 실패, 임계 시간 초과 | `HTTP_CALL` | #379 |
| Hibernate PostCommit 리스너 | `@Tracked` 엔티티의 생성·변경·삭제 (`@TrackedField`만, 변경 전후 값) | `STATE_CHANGED` | #380 |
| `@Modifying` Aspect | `@Tracked` 엔티티 리포지토리의 bulk·native 쿼리: 메서드명, ID와 숫자 파라미터, 영향받은 행 수. 영향 행이 0이면 실패로 기록 | `STATE_CHANGED` / `BUSINESS_FAILURE` | #380 |
| Kafka producer | 발행 | `MESSAGE_SENT` | #382 |
| Kafka consumer 래퍼 | 수신, 처리 결과 | `MESSAGE_RECEIVED`, `RECEIVED_NO_EFFECT` | #382 |
| DLQ 이동 직전 | 재시도를 모두 소진한 메시지 | `SYSTEM_FAILURE` | #382 |
| StuckDetector | 멈춤, 복구 | `STUCK`, `RECOVERED` | #384 |

Hibernate 리스너를 거치지 않는 bulk·native 쿼리(Aspect로 보완해야 하는 것):
- `BuyerWalletRepository`: 지갑 차감 (native)
- `ProductOptionRepository`, `ProductStockAllocationRepository`: 재고 할당 (JPQL bulk)

### 6.2 로그 스키마 (JSON)

필드 이름은 고정한다. 필드를 추가하는 것은 괜찮지만, 이름을 바꾸거나 지우면 조회와 대시보드가 깨진다.

```json
{
  "timestamp": "2026-11-05T10:15:30.123+09:00",
  "level": "WARN",
  "app": "paldogotgan",
  "module": "wallet",
  "eventType": "BUSINESS_FAILURE",
  "step": "WALLET_PAYMENT",
  "traceId": "111634463317819397",
  "orderId": 1024,
  "shipmentId": null,
  "outcome": "FAILURE",
  "message": "지갑 잔액이 부족해 결제하지 못했다",
  "error": { "code": "INSUFFICIENT_BALANCE", "type": "RestApiException", "stack": [] },
  "entity": null,
  "changes": null
}
```

- `STATE_CHANGED`일 때는 `entity`(이름과 ID)와 `changes`(`{필드: [이전, 이후]}`)를 채운다.
- `stack`은 깊이 30까지만 남긴다.

### 6.3 추적 대상 표시 (어노테이션)

| 어노테이션 | 위치 | 의미 |
|---|---|---|
| `@Tracked` | 엔티티 | 이 엔티티의 변경을 기록한다 |
| `@NotTracked` | 엔티티 | orderId나 shipmentId가 있지만 기록하지 않는다 (명시적 제외) |
| `@TraceKey` | 필드 | 이 필드 값으로 흐름을 묶는다 (예: `orderId`) |
| `@TrackedField` | 필드 | 변경 값을 기록할 필드 (화이트리스트) |

- 묶는 키를 찾는 순서: `@TraceKey`를 먼저 보고, 없으면 MDC를 본다.
- ArchitectureTest: orderId나 shipmentId 필드가 있는 엔티티에 `@Tracked`도 `@NotTracked`도 없으면 테스트가 실패한다. 모듈 개발자는 이 테스트가 실패했을 때만 로깅 시스템을 알면 된다.
- 첫 적용 대상: Order, OrderItem, Shipment, BuyerWallet, ProductOption, ProductStockAllocation. 각 모듈 담당자가 리뷰한다.

## 7. 출력과 저장

### 7.1 앱 로그 출력 (#378)

| 항목 | 값 |
|---|---|
| 성공 appender | `AsyncAppender`, 큐 8192, `neverBlock` (넘치면 버림) |
| 실패 appender (WARN 이상) | 로컬 파일에 동기로 기록 |
| 크기 상한 | 필드 1KB, 이벤트 8KB, 스택 깊이 30 |
| 같은 에러 억제 | 같은 에러 키가 1분에 N회를 넘으면 "N건 생략" 요약 한 건으로 바꾼다 (TurboFilter) |
| 샘플링 | HTTP 성공 로그 10% |
| 마스킹 | 인코더에서 키 패턴(name, phone, address, email, receiver*)을 일괄 마스킹 |
| 롤링 | 파일 100MB, 전체 2GB, 7일 |
| 관측 | 버린 로그 건수를 지표로 노출 |

모든 값은 설정으로 바꿀 수 있게 하고, 100 req/s 부하 테스트로 검증한다. 지표는 #374에서 본다.

### 7.2 수집 스택 (#381, 운영 서버)

| 구성 | 위치 | 설정 |
|---|---|---|
| Alloy | 앱 EC2 | 로그 파일을 읽어서 보낸다. WAL 상한, `mem_limit` |
| Loki 단일 바이너리 | 로그 전용 EC2 | S3 저장소, `ingestion_rate_mb`, `per_stream_rate_limit`, `mem_limit` |
| S3 | AWS | 수명 주기 정책 |
| Grafana | 로그 EC2 | 주문별 조회, 실패 추이, 버린 로그 수 대시보드 |
| Terraform | `infra` | 로그 EC2, S3 버킷, IAM |

- **Loki 라벨은 `app`, `module`, `level`, `eventType` 4개로 고정한다.** orderId, traceId, shipmentId는 라벨로 쓰지 않고 JSON 본문에서 필터한다(`| json | orderId="1024"`). 라벨을 추가하려면 카디널리티를 먼저 검토한다.
- 보존 기간: 실패 90일, 성공 14일 (compactor 설정)
- Loki가 멈춰도 앱은 파일에만 쓴다. Alloy가 재기동 후 밀린 로그를 따라잡는다.

## 8. 감지 (2단계: #382~#384)

### 8.1 처리 영수증 (#382)

후속 이벤트가 없는 홉(예: 결제 완료 → 배송 생성)도, 받는 쪽이 실제로 처리했는지 모듈 코드를 바꾸지 않고 확인한다.

- 공통 컨슈머 래퍼가 처리 중에 일어난 `@Tracked` 변경 건수를 센다. 처리가 끝나면 `flowwatch.receipt`로 영수증을 비동기 fire-and-forget으로 발행한다.
- 영수증 내용: topic, offset, orderId, shipmentId, consumerGroup, outcome, 변경된 엔티티
- outcome 종류:
  - `APPLIED`: 변경 1건 이상
  - `NO_EFFECT`: 변경 0건. `RECEIVED_NO_EFFECT`로 기록하며 알람은 없고 대시보드 지표로만 본다
  - `FAILED`
- 토픽 보존 기간 3일. 영수증 발행이 실패해도 비즈니스 처리는 계속된다.

### 8.2 flowwatch 모듈 (#383)

- 새 모듈 `flowwatch`(패키지 `com.ok.flowwatch`)다. 이름 이유는 ADR에 있다. 스키마는 `paldogotgan_flowwatch`이고 monolith에 포함한다.
- 관찰 전용 컨슈머 그룹 `flowwatch-observer`로 주문 흐름 토픽과 `flowwatch.receipt`를 구독한다. 비즈니스 컨슈머와 그룹이 달라서 서로 영향을 주지 않는다.

`order_flow` 테이블:

| 컬럼 | 의미 |
|---|---|
| `order_id`, `shipment_id` | 흐름 키 |
| `last_step`, `last_step_at` | 마지막으로 확인한 단계와 시각 (이벤트 발생 시각 기준) |
| `expected_next` | 기다리는 다음 단계 |
| `deadline` | `last_step_at` + 기준 시간 |
| `status` | `IN_PROGRESS`, `STUCK`, `COMPLETED` |

- 이벤트 발생 시각을 기준으로 앞으로만 갱신한다. 순서가 뒤바뀌어 늦게 온 이벤트는 무시한다.
- 완료된 흐름은 30일 뒤 정리한다.

### 8.3 흐름 정의 (yaml)

```yaml
flowwatch:
  flow:
    - step: ORDER_CREATED          # 결제 대기
      next: WALLET_PAID
      timeout: 5m
    - step: WALLET_PAID
      next: ORDER_PAID
      timeout: 5m
    - step: ORDER_PAID
      next: SHIPMENT_CREATED       # delivery 영수증으로 확인
      timeout: 5m
    - step: SHIPMENT_CREATED
      next: SHIPPED
      timeout: 3d
    - step: SHIPPED
      next: DELIVERED
      timeout: 2d
    - step: DELIVERED
      next: PURCHASE_CONFIRMED
      timeout: 3d1h
    - step: PURCHASE_CONFIRMED
      next: PAYOUT_APPLIED         # payout 영수증으로 확인
      timeout: 5m
  entity-rules:                    # 이벤트가 없어도 엔티티 상태로 직접 확인
    - entity: Order
      status: PAYMENT_PENDING
      timeout: 5m                  # 기본 기준 필드는 updatedAt
    - entity: Shipment
      status: PREPARING
      timeout: 3d
    - entity: Shipment
      status: SHIPPING
      timeout: 2d
    - entity: Shipment
      status: DELIVERED
      timeout: 3d1h
      time-field: deliveredAt      # 기준 필드를 바꿀 때
```

- 형식은 예시다. 실제 키 이름은 #383, #384를 구현할 때 정한다.
- 모든 엔티티가 `BaseTime`이나 `BaseIdAndTime`을 상속하므로 `updatedAt`을 기본 기준 필드로 쓸 수 있다.

### 8.4 멈춤 감지와 알람 (#384)

- StuckDetector는 `deadline`이 지난 `IN_PROGRESS` 흐름을 찾아 `STUCK`으로 기록하고 알린다. 알리기 전에 엔티티 상태를 다시 확인해서, 영수증이 유실돼서 생기는 오탐을 거른다.

| 알람 정책 | 값 |
|---|---|
| 같은 주문 | 처음 멈췄을 때 1회만 |
| 복구 | `RECOVERED` 1회 |
| 같은 원인 | 5분 단위로 묶어 요약 1건 |
| 채널 상한 | 분당 10건 |
| 추가 알람 | consumer lag, DLQ 적재 |

DLQ 처리 순서: 재시도를 모두 소진한다 → 실패 로그를 남긴다 → DLQ로 보낸다 → 관리자에게 알린다.

## 9. 진행 순서와 이슈

```mermaid
flowchart LR
  376["#376 컨텍스트·TraceID"] --> 377["#377 전파"]
  376 --> 378["#378 JSON·폭증 방지"]
  378 --> 379["#379 실패 경계"]
  377 --> 380["#380 엔티티 변경"]
  378 --> 380
  378 --> 381["#381 운영 로그 스택"]
  K["Kafka 전환<br/>(이슈 미생성)"] --> 382["#382 Kafka 헤더·영수증"]
  376 --> 382
  382 --> 383["#383 flowwatch 모듈"]
  383 --> 384["#384 멈춤 감지·알람"]
```

| 단계 | 이슈 | 결과 |
|---|---|---|
| 1단계 수집 | #376 ~ #381 | Grafana에서 orderId로 주문 흐름과 실패를 조회한다. 지금의 Modulith 이벤트 구조에서도 동작한다 |
| 2단계 감지 | #382 ~ #384 | 멈춘 주문을 Slack으로 알린다. Kafka로 옮긴 뒤에 시작한다 |

에픽 완료 조건 (#375):
- 주문 하나의 생성부터 구매 확정까지를 Grafana에서 orderId 하나로 조회한다.
- 로그 스택이 장애이거나 부하가 걸려도 실패 로그가 유실되지 않는다.
- 기준 시간을 넘긴 주문은 Slack 알림 1회, 복구되면 RECOVERED 알림 1회가 온다.
- 모듈 비즈니스 코드의 변경은 추적 어노테이션을 붙이는 것뿐이다.

## 10. 확장할 때 고려

| 항목 | 내용 | 다시 볼 시점 |
|---|---|---|
| CDC (Debezium) | 앱 밖에서 일어나는 쓰기까지 잡는다. 모듈 경계, binlog 설정, Kafka Connect의 운영·메모리 부담 때문에 보류했다 | 수동 SQL이나 다른 언어 서비스처럼 앱 밖 쓰기가 생길 때 |
| 로그 Kafka 토픽 | 로그를 토픽으로 보내 상태 프로젝션이나 모듈 간 조인 분석을 한다. JSON 스키마를 메시지처럼 설계해 둔 이유다 | Loki 쿼리로 분석하기 어려워질 때 |
| S3 + Athena | 원본을 아카이브하고 장기 조회한다 | 보존 기간보다 오래된 데이터를 분석해야 할 때 |
| 배송사 연동 | Delivery Tracker 계약(GraphQL `track`, `registerTrackWebhook`, 48시간 만료를 24시간마다 갱신) 기준 어댑터와 시뮬레이터. 콜백에는 carrierId와 trackingNumber만 오므로 `track`을 다시 조회하고, 중복과 순서 역전을 판정하고, 1초 안에 202로 응답한다. (미확인) 플랜별 웹훅 제공 여부 | 배송사 연동 이슈를 만들 때 |
| 사가 확인 이벤트 | 받는 쪽 확인을 영수증 대신 도메인 이벤트로 한다 | 사가를 설계할 때 |
| 다중 인스턴스 | Snowflake 노드 ID 할당, StuckDetector 중복 실행 방지 (ShedLock 등) | 모듈별 서버로 나눌 때 |

## 11. 열린 항목

| 항목 | 상태 |
|---|---|
| 주문 흐름 이벤트 Kafka 전환 이슈 | 없음. 만든 뒤 #375 또는 #382에 연결한다 |
| 운영 서버 지표(Prometheus) | 맡는 이슈가 없다 |
| Grafana를 #374(개발)와 #381(운영)에서 따로 둘지 | 운영 Grafana는 #381에서 둔다. 하나로 합치는 것은 미정이다 |
| 같은 에러 억제 횟수 N, 샘플링 비율 | 부하 테스트(#378)로 정한다 |
| 로깅 규칙의 `CONVENTION.md` §7.4 반영 | #378에서 한다 (라벨 정책, 인코더 마스킹, eventType) |
