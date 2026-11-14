# 정산 모듈 규칙

## 설계 전제: 규모

정산은 **하루 최소 10만 명이 이용하는 사이트**라고 가정하고 설계한다. (사용자 결정, 2026-11-14)

- 기능을 정할 때 "지금 데이터가 적어서 괜찮다"로 판단하지 않는다. 위 규모에서 데이터가 쌓였을 때도 버티는지 본다.
- 실행할 때마다 운영 시작 이후 쌓인 기록 전체를 읽는 방식은 새로 만들지 않는다. 새로 생긴 것만 읽거나(예: 반영한 마지막 ID 뒤만), 인덱스로 필요한 범위만 읽는다. 예) 막힌 주문 집계 표(#582)는 Job마다 전체를 다시 계산하지 않고 바뀐 주문만 고친다.
- 이미 있는 전체 조회(예: 스냅샷의 `countFailedRunsByOrder`)는 #304(대량 처리·성능 점검)에서 다룬다.

# 정산 패키지 구조 (헥사고날)

정산(`payout`) 모듈의 클래스를 만들거나 옮길 때 따르는 규칙이다. `structure.md`의 레이어 패키지(`controller`, `service`, `repository`, `event`)와 발행 인터페이스 위치 규칙 대신 이 파일을 따른다. 역할별 접미사와 모듈 간 규칙은 `structure.md` 그대로다.
결정과 이유: `docs/decisions/payout/441-payout-hexagonal.md`, `469-payout-arch-test-gaps.md`, `485-payout-skip-wait-run-type.md`, `390-payout-lower-bound-scheduled-run.md`.

## 패키지

| 패키지 | 두는 것 |
|---|---|
| `domain` | 엔티티(JPA 어노테이션 그대로), 정책(`PayoutPolicy`) |
| `domain/type` | 입구 중 배치와 application·엔티티가 같이 쓰는 enum(`PayoutRunType`). enum만 둔다. 웹·이벤트 입구는 쓰지 않는다 |
| `application/port/in` | 입력 포트(`*UseCase`)와 그 값(`*Command`, `*Query`, `*Result`) |
| `application/port/out` | 출력 포트(`*Repository`, `*QueryRepository`, 발행 인터페이스)와 그 값(`*Row`, 발행 값 `SellerPayoutCreated`) |
| `application/service` | 유스케이스 구현(`*Service`), 검증기, 조회 결과 변환(`PayoutQueryResultMapper`) |
| `adapter/in/web` | 컨트롤러, `*Spec`, 요청·응답 DTO(`dto/`) |
| `adapter/in/event` | Spring·Kafka 리스너, Kafka 메시지 DTO(`dto/`) |
| `adapter/in/batch` | 정산 Job 설정, 시작기(`PayoutJobRunner`), 스케줄러 |
| `adapter/out/persistence` | Spring Data JPA 저장소(`*JpaRepository`), Querydsl 조회, JPA로 할 수 없는 곳의 네이티브 SQL(아래 "주의") |
| `adapter/out/event` | 이벤트 발행 구현체 |
| `config`, `exception` | 모듈 전용 설정, `PayoutErrorCode` |

- 위 표에 없는 하위 패키지를 만들지 않는다. 꼭 필요하면 `ArchitectureTest`의 `정산_클래스는_정해진_패키지에만_있다` 허용 목록과 관련 규칙, `ArchitectureRuleEdgeTest`를 함께 고친다.
- `com.ok.payout` 밖(예: `com.ok.relay`)에 정산 모듈 클래스를 두지 않는다. `PayoutApplication`만 예외다.
- 값은 그 값이 지나가는 포트 옆에 둔다. delivery처럼 `application/dto`에 모으지 않는다.

**이유**: 값을 한곳에 모으면 dto ↔ port.out 순환이 생긴다. 패키지 규칙은 패키지 이름으로 검사하므로, 목록에 없는 하위 패키지를 거치면 규칙을 피할 수 있다(#469에서 실행으로 확인).

## 의존 방향

- `adapter.in` → `application.port.in` → `application.service` → `application.port.out` ← `adapter.out`. 서비스는 JPA 저장소, JDBC, Kafka, Spring Batch를 모른다.
- `port.in`은 `port.out`을 모른다. 조회 Row → Result 변환은 서비스(`PayoutQueryResultMapper`)가 한다.
- 컨트롤러와 리스너는 `port.in`만 안다. 서비스 구현체, `port.out`, `domain`을 직접 쓰지 않는다.
- 배치를 뺀 입구(web, event)는 배치 중 `PayoutJobRunner`만 쓴다. 배치는 `domain` 중 `PayoutPolicy`와 `domain/type`만 쓴다.
- `domain/type`에는 enum만 두고, 정산의 다른 패키지와 JPA·Spring을 모른다. 웹·이벤트 입구는 `domain/type`도 쓰지 않는다(`api.md`: 컨트롤러는 domain 타입을 쓰지 않음).
- `com.ok` 중 `payout`과 `common`만 안다.
- `common.event`의 이벤트 클래스는 `adapter/in/event`, `adapter/out/event`에서만 쓴다.
- 발행 도구(`ApplicationEventPublisher`, `KafkaOperations`)는 `adapter/out/event`에서만 쓴다.

**이유**: 입구와 외부 연결이 서비스를 거쳐서만 만나야 연동 방식(Spring 이벤트 → Kafka, 모듈 분리)을 바꿔도 서비스가 바뀌지 않는다. 이 규칙은 정산 `ArchitectureTest`가 검사한다.

## 주의

- `adapter/in/web`은 경로가 `controller`가 아니라 `api.md`가 자동으로 로드되지 않는다. 직접 읽고 따른다.
- 저장소 포트에 메서드를 더하면 쿼리 애노테이션(`@Query`, `@Modifying`, `@EntityGraph`)은 `adapter/out/persistence`의 `*JpaRepository`에 `@Override`로 다시 선언한다. 포트에는 Spring Data를 두지 않는다.
- 네이티브 SQL은 되도록 쓰지 않는다. 조회는 Querydsl, 저장은 Spring Data JPA로 한다. JPA로 할 수 없을 때만 쓰고, 그 메서드 주석에 "왜 JPA로 안 되는지"를 적는다. 지금 쓰는 곳은 아래와 같다.
  - JPA 엔티티가 아닌 Spring Batch 실행 기록(`BATCH_*`)을 읽음: `PayoutQuerydslRepository`의 `findLastCompletedScheduledRequestedAt`(#390), `countFailedRunsByOrder`(#549, 성공한 실행만 걸러 주문별로 세는 일을 DB 안에서 끝내려고. 자바에서 거르면 실패가 쌓일수록 옮기는 줄이 늘어남)와 같은 SQL 앞부분에 주문 조건만 더한 `countFailedRunsOfOrder`(#568, 해결 API의 멈춤 판정)·`countFailedRunsOfOrders`(#582, 막힌 주문 집계 표의 멈춤 판정을 주문 목록으로 한 번에)
  - MySQL 전용 "없으면 넣기"(`INSERT ... ON DUPLICATE KEY`): `PayoutCandidateItemJpaRepository`의 `insertShippingFeeIfAbsent`(#309)
  - 이유: SQL 문자열은 표·열 이름을 틀려도 컴파일 때 잡히지 않고, 표 이름 앞 스키마(`PayoutSchema.NAME`)를 빠뜨리면 로컬 테스트는 통과해도 운영에서만 실패한다(`entity.md`). (사용자 결정, 2026-11-13)
- 정산 기록 표(스냅샷, 실패 기록, 해결 기록, 정산 등)는 쌓기만 하고 고치거나 지우지 않는다(`@Immutable`). 수정을 허용하는 표는 아래뿐이다.
  - `payout_outbox`: 발송 상태를 바꿈 (#309)
  - 막힌 주문 집계 표 묶음 `payout_blocked_order`, `payout_blocked_order_cause`, `payout_blocked_order_refresh`: 수정·삭제 허용 (#582). 원본 기록에서 계산한 조회용 사본(읽기 모델)이라 틀리면 원본에서 다시 계산해 바로잡을 수 있다. 스냅샷은 이 표가 아니라 원본으로 판정하므로, 표가 낡아도 돈이 잘못 나가지 않는다. (사용자 결정, 2026-11-14)

## 팀 규칙과 다르게 쓰는 것

정산 모듈에서는 아래 두 가지를 팀 규칙(`.claude/rules/`)과 다르게 쓴다. 나머지는 팀 규칙 그대로다.

| 팀 규칙 | 정산에서 | 이유 |
|---|---|---|
| `service.md` 로그: `ERROR`는 처리되지 못한 예외(서버 오류)에만 쓴다 | 정산 코드·정책이 틀렸다는 **시스템 문제 알림**은 처리를 계속하더라도 `ERROR`로 남긴다. 메시지는 `[정산-시스템문제]`로 시작한다(예: `[정산-시스템문제] 수수료 불일치 orderItemId={}`) | 사람이 고쳐야 할 일은 코드 수정뿐이라 개발자가 바로 알아야 한다. 이 모양의 `ERROR`를 슬랙 알림(#302)에 연결한다 (#391) |
| `testing.md` 파라미터화: 입력값은 `@MethodSource`로 제공한다 | enum의 **모든 값**을 하나씩 확인하는 테스트는 `@EnumSource`를 쓴다 | 값이 늘어나도 테스트가 저절로 따라간다. `@MethodSource`로 목록을 손으로 적으면 새 값을 빠뜨릴 수 있다 (#391) |
