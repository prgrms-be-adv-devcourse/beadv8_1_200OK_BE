---
issue: 375
module: common
summary: 주문 흐름을 orderId로 묶어 추적하고 실패와 멈춘 흐름을 감지하는 로깅 시스템을, 경계 지점 자동 수집과 관찰 전용 flowwatch 모듈로 설계했다. 모듈 코드 변경을 최소화하고 실패 로그를 놓치지 않는 것을 우선했다.
status: 진행 중
date: 2026-11-05
paths: [common/src/main/java/com/ok/common, common/src/main/java/com/ok/common/exception/ApiControllerAdvice.java, order/src/main/java/com/ok/order/order/client/InternalRestClient.java, wallet/src/main/java/com/ok/wallet/repository/BuyerWalletRepository.java, product/src/main/java/com/ok/product/product/repository/ProductOptionRepository.java, delivery/src/main/java/com/ok/delivery/application/port/out/CarrierClient.java]
related: [99, 374, 376, 377, 378, 379, 380, 381, 382, 383, 384]
---

> 이 문서는 #375 에픽 기획에서 정한 결정과 이유를 남긴 기록이다. 코드 변경 없이 기획 대화에서 정했고, 하위 이슈(#376~#384)를 구현할 때 세부 결정은 각 이슈의 ADR에 남긴다. 시스템 전체 구성(무엇을 어떻게)은 `docs/design/order-flow-tracking.md`에 있고, 이 문서는 이유만 담는다. 코드가 바뀌었으면 코드가 우선이다.

## 배경

시스템 정의(사용자 결정): 분산 환경에서 코드의 직접 변경을 최소화하여 데이터의 흐름을 파악하고, 성공보다 실패 데이터를 놓치지 않고 관리하여 빠르게 장애를 파악하고 대응하는 시스템이다.

- 주문 → 지갑 결제 → 재고 → 배송 → 구매 확정 → 정산은 모듈과 진입점(HTTP, 스케줄러, 배치, 외부 콜백)이 달라도 하나의 흐름이다. 모듈 간 통신은 Spring Modulith 이벤트에서 Kafka로 옮길 계획이다.
- 작성 시점에 트레이싱, MDC, 구조화 로그, 로그 수집 스택, 주문·배송 상태 이력 테이블이 없음을 확인했다.
- 범위 밖: 고객·CS용 화면, 배송사 실제 연동 구현, Kafka 전환 자체, 운영 서버 지표(Prometheus) 수집.
- 기존 TraceID 에픽 #99는 Kafka 전환 전 구조 기준이라 닫고 이 에픽으로 다시 시작했다(사용자 결정).

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 상태의 진실 원천 | 엔티티(Order, OrderItem, Shipment)가 갖고, 로그는 append-only 기록 | 로그가 유실·지연되어도 주문 상태가 틀어지지 않는다. 이벤트 소싱 비용을 지지 않는다 |
| 묶는 키 | orderId(+shipmentId). traceId는 Snowflake로 요청·실행 단위를 잇는다(사용자 결정) | traceId는 스케줄러, 외부 콜백처럼 새로 시작하는 흐름에서 바뀐다. 주문 하나에 배송이 판매자별로 여러 건이라 shipmentId가 필요하다 |
| 수집 지점 | HTTP 필터·클라이언트, Kafka 인터셉터, 예외 경계, Hibernate PostCommit 리스너, `@Modifying` Aspect를 하나의 MDC 컨텍스트와 JSON 스키마로 묶는다(사용자 결정) | 잔액 부족, 타임아웃, 발행 실패처럼 엔티티를 바꾸지 않는 실패가 많다. 성공 흐름도 남겨야 어디서 멈췄는지 안다 |
| 엔티티 변경 감지 | PostCommit Insert/Update/Delete 리스너 | 커밋이 확정된 뒤에만 실행되므로 롤백된 변경이 성공으로 기록되지 않는다 |
| bulk/native 쿼리 | `@Tracked` 엔티티 리포지토리의 `@Modifying` 메서드만 Aspect로 기록 | 지갑 차감과 재고 할당이 이 쿼리라 리스너를 우회한다. `@Modifying`만 기준으로 하면 약관 동의 같은 무관한 쓰기까지 남는다(사용자 우려) |
| 추적 범위 표시 | 어노테이션(`@Tracked`, `@NotTracked`, `@TraceKey`, `@TrackedField`) + ArchitectureTest 강제(사용자 결정) | 이름 변경에 강하고 코드에서 보인다. orderId·shipmentId 필드가 있는 엔티티에 표시가 없으면 테스트가 알려 주므로, 모듈 개발자는 필요한 순간에만 알면 된다 |
| 필드 기록 | `@TrackedField` 화이트리스트만 | 누가 개인정보 필드를 추가해도 기본적으로 새지 않는다 |
| 성공과 실패 | 성공은 단계별 한 줄을 비동기로, 큐가 차면 버린다. 실패는 상세하게 로컬 파일에 동기로 쓴다 | 성공은 놓쳐도 되지만 실패는 놓치면 안 된다(사용자 결정). 비즈니스 스레드를 막지 않는다 |
| 개인정보 | 로그 인코더 단계에서 키 패턴으로 일괄 마스킹(사용자 결정) | 외부 응답 원본이나 요청 요약에 섞이는 값까지 한곳에서 막는다 |
| 폭증 방지 | 큐·필드·이벤트 크기 상한, 같은 에러 억제, HTTP 성공 샘플링, 롤링 상한, Loki 라벨 4개 고정 | 과거에 로그로 메모리가 찬 경험이 있다(사용자). 상한값은 부하 테스트로 검증한다 |
| 저장 | 앱 EC2에는 수집 에이전트만 두고, 별도 EC2에 Loki 단일 바이너리 + S3 저장소를 둔다(사용자 결정) | 앱 EC2는 MySQL, Kafka와 메모리를 나눠 쓴다. 로그 스택에 장애가 나도 앱은 파일에 쓰기만 한다 |
| 멈춤 감지 위치 | 새 flowwatch 모듈이 관찰 전용 컨슈머 그룹으로 `order_flow`를 관리한다 | 없는 로그는 에러를 내지 않으므로 다음 단계를 기다리는 쪽이 필요하다. 컨슈머 그룹이 따로라 비즈니스에 영향이 없다 |
| 받는 쪽 처리 확인 | 공통 컨슈머 래퍼가 처리 영수증(APPLIED/NO_EFFECT/FAILED)을 자동 발행(사용자 결정) | 후속 이벤트가 없는 홉(결제 완료 → 배송 생성)도 모듈 코드 변경 없이 확인한다 |
| 알람 | 주문당 1회, RECOVERED 1회, 같은 원인은 5분 단위로 묶어 요약, 채널 상한 | 반복되는 알람은 무시된다 |
| 진행 순서 | 1단계 수집(#376~#381)을 먼저 하고, 2단계 감지(#382~#384)는 주문 흐름 이벤트의 Kafka 전환 뒤에 한다 | 수집은 Modulith 이벤트 상태에서도 동작한다 |
| 모니터링 범위 | #374는 개발 환경 지표, #381은 운영 서버 로그(사용자 결정) | Grafana 위치가 겹치던 것을 환경 기준으로 나눴다 |

## 버린 대안

- **변경 감지:** Hibernate Interceptor와 JPA `@PostUpdate`는 커밋 전에 실행되어 롤백된 변경도 남기고, `@PostUpdate`는 변경 전 값도 모른다. Envers는 엔티티마다 감사 테이블과 마이그레이션이 필요하고 비즈니스 DB에 쓰기를 더한다. datasource-proxy는 SQL 문장 단위라 읽기 어렵다.
- **추적 범위 표시:** yaml 중앙 설정은 모듈 개발자가 몰라도 되지만 문자열이라 낡기 쉽고 코드에서 안 보인다. 필드명 관례 자동 감지는 orderId가 없는 BuyerWallet, ProductOption을 놓친다.
- **CDC(Debezium):** 앱 밖의 쓰기까지 앱 영향 없이 잡는다. 그러나 flowwatch가 다른 모듈의 테이블 구조에 의존하게 되어 메시지 계약만 공유하는 모듈 경계 규칙과 충돌한다. MySQL binlog 설정 변경이 필요하고, Kafka Connect 운영과 메모리 부담이 있으며, traceId를 모른다. 수동 SQL이나 다른 언어 서비스처럼 앱 밖 쓰기가 생기면 확장할 때 다시 검토한다.
- **로그 저장 경로:** 로그를 Kafka 토픽으로 보내는 방식은 비즈니스 메시지와 같은 브로커를 써서 로그량이 비즈니스를 지연시킬 수 있고, 컨슈머와 저장소를 직접 만들어야 한다. 대신 JSON 스키마를 메시지처럼 설계해 나중의 발전 경로로 남긴다. Grafana Cloud는 로그가 외부로 나가고 보존 기간이 짧다(미확인: 무료 티어 조건은 작성 시점 기억 기준). ELK는 메모리 요구가 가장 크다. Loki를 앱 EC2에 함께 두면 로그 폭증이 비즈니스에 번진다. CloudWatch는 수집량 과금과 AWS 종속이 있다. S3+Athena만 쓰면 실시간 조회와 알람이 약하다.
- **받는 쪽 확인:** delivery가 `ShipmentCreated`를 발행하거나 사가 확인 이벤트를 두면 모듈 개발자가 로깅 시스템을 알고 구현해야 한다(사용자 지적). 컨슈머 그룹 오프셋 조회만으로는 받았는지만 알고 처리 결과를 모른다. 대상 모듈 조회 API 호출은 모듈 쪽 작업이 생긴다.
- **감지기 위치:** common에 두면 모듈별 서버로 나뉜 뒤 서버마다 감지기가 돌아 알람이 중복된다. Loki 쿼리로 "A는 있는데 B가 없음"을 찾는 방식은 조인 쿼리에 약하다.
- **모듈 이름:** `tracking`은 delivery에서 이미 배송 추적(송장 번호, `TrackingUseCase`) 뜻으로 쓰여 혼동되어 버렸다. `observer`는 Spring `Observation` API와, `monitoring`은 #374 지표 모니터링과, `audit`은 변경 감사 로그와 헷갈리고, `orderflow`는 환불·정산 흐름으로 넓히기에 좁다. 흐름을 지켜보다 멈추면 알리는 역할을 그대로 담은 `flowwatch`로 정했다(사용자 결정). 수집 쪽 어노테이션(`@Tracked` 등)은 엔티티의 추적 대상 표시라 모듈 이름과 맞추지 않는다.
- **배송사 연동:** 택배사별 직접 구현은 계약과 화면 변경 부담이 커서 버렸다. Delivery Tracker 계약 기준 어댑터와 시뮬레이터 방향으로 계획만 남겼다(미확인: 플랜별 웹훅 제공 여부).

## 건드릴 때 주의

- 지갑 차감(`BuyerWalletRepository`)과 재고 할당(`ProductOptionRepository`)은 `@Modifying` bulk/native 쿼리라 Hibernate 리스너에 잡히지 않는다. 이 쿼리를 바꾸거나 새 bulk 쿼리를 추가하면 Aspect 기록 대상인지 확인한다.
- orderId, traceId, shipmentId를 Loki 라벨로 올리지 않는다. 주문마다 스트림이 생겨 Loki 메모리가 폭발한다. 이 값들은 JSON 본문에 둔다.
- 흐름을 묶는 키는 traceId가 아니라 orderId다. traceId는 비동기 시작점에서 새로 발급된다.
- 실패 appender는 동기 파일 기록을 유지한다. 성능 때문에 비동기로 바꾸면 실패 로그를 놓치지 않는다는 목표가 깨진다.
- 처리 영수증은 fire-and-forget이라 유실되면 STUCK 오탐이 생길 수 있다. 감지기가 알람 전에 다시 확인하는 경로를 유지한다.
- 모듈 간 HTTP 호출은 추적 헤더(traceId, orderId)를 실어야 받는 쪽 bulk 쿼리가 주문에 묶인다. 새 내부 클라이언트는 `InternalRestClient`를 거친다.
