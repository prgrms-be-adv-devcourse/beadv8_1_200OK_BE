---
issue: 441
module: payout
summary: 정산을 포트·어댑터 구조로 옮겨 바깥 기술(Spring 이벤트·Kafka·JPA·배치) 변경이 정산 로직에 닿지 않게 하고 ArchitectureTest로 강제했다. 엔티티는 불변 장부라 분리하지 않았다.
status: 확정
date: 2026-11-05
paths: [payout/src/main/java/com/ok/payout, payout/src/test/java/com/ok/payout/ArchitectureTest.java]
related: [339, 343, 309, 421]
---

> 이 문서는 #441 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

Kafka 전환(#339) 뒤 정산이 바깥과 닿는 세 곳(받기·보내기·저장)을 다루는 방식이 달랐다. 발행은 이미 인터페이스(`PayoutEventPublisher`) 뒤에 있어 Kafka 전환 때 서비스가 주석만 바뀌었지만, 받는 쪽은 한 리스너 클래스에 Spring 입구(`on`)와 Kafka 입구(`onKafka`)가 섞였고, 받은 값 검사가 서비스 트랜잭션 안에 있어 잘못된 이벤트를 정상 종료하지 못했다(#421). 남은 작업(#343 Spring 입구 제거, #309 outbox, #304 일괄 저장)이 모두 바깥쪽 변경이라 그 전에 경계를 만든다.

- 범위: 정산 모듈 구조만. 기능·API·DB 스키마·마이그레이션은 그대로. 정산 밖은 monolith 테스트의 import 한 줄.
- 범위 밖: JPA 엔티티·도메인 분리, `common` 의존 정리(과도기로 봄), 구조 안내 문서(`CLAUDE.md`, `docs/DEVELOPMENT.md`, `.claude/rules/structure.md`, 팀 확인 후), #421 값 검사.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 엔티티 분리 | 하지 않음, 포트·어댑터만 적용 (사용자 결정) | 엔티티 10개 모두 `@Immutable` 장부이고 판단 로직은 `isSystemShare()`와 수동 조정 값 검사 정도다. 규칙은 이미 엔티티 밖(`PayoutAmountValidator`, `PayoutDraftValidator`, `PayoutPolicy`)에 있다. 분리하면 매퍼와 묶음 저장·QueryDSL 결과 변환만 는다 |
| 입력 포트 | 서비스마다 `*UseCase` 하나(3개) (사용자 결정) | delivery와 같다. `PayoutService` 메서드 18개를 입구별로 나누는 일은 구조 이동 범위를 넘는다 |
| 배치 위치 | `adapter/in/batch` (사용자 결정) | Tasklet은 서비스를 부르기만 하는 입구다. `application`에 두면 Spring Batch 타입 때문에 "서비스는 바깥 기술을 모른다"에 예외가 생긴다 |
| 배치의 domain 사용 | `PayoutPolicy`만 허용 (사용자 결정) | 배치가 쓰는 domain 타입은 배치 크기·대기 일수 상수뿐이다. `PayoutPolicy`를 domain 밖으로 빼지 않은 이유는 수수료 계산·대기 일수 같은 정산 규칙이 함께 있어서다 |
| 관리자 실행 API | 컨트롤러가 `PayoutJobRunner`를 직접 호출 (사용자 결정) | 둘 다 `adapter/in` 층이고 Runner는 계산하지 않는 배치 시작 버튼이다 |
| Spring·Kafka 입구 | 리스너를 `*EventListener`(Spring)와 `*KafkaListener`로 분리 | #343에서 Spring 입구 클래스만 지우면 된다 |
| 저장소 포트 | 포트는 순수 인터페이스(이름은 기존 리포지토리 그대로), `*JpaRepository`가 `JpaRepository`와 포트를 함께 상속 | delivery와 같다. 실행 객체가 기존 Spring Data 프록시라 쿼리·트랜잭션이 그대로이고, 서비스 코드는 import만 바뀐다 |
| 쿼리 애노테이션 | 포트에는 메서드 모양만, `@Modifying @Query`·`@EntityGraph`는 어댑터에서 `@Override`로 다시 선언 | 포트가 Spring Data를 몰라야 한다. `insertShippingFeeIfAbsent`의 네이티브 쿼리가 같은 묶음 배송비 동시 처리 방어다 |
| 포트가 주고받는 값의 위치 | 입력 포트의 Command·Query·Result 15개는 `application/port/in`, 조회 Row 7개와 `SellerPayoutCreatedCommand`는 `application/port/out` (사용자 결정) | 값을 한곳(`application/dto`)에 모으면 결과 값(`Result.from(Row)`)과 발행 포트(명령 사용) 때문에 dto ↔ port.out 순환이 생긴다. 값을 그 포트 옆에 두면 방향이 port.in → port.out 하나이고, 어떤 값이 어느 포트를 지나는지 위치로 보인다. delivery는 `application/dto`를 쓴다 |
| 페이징 타입 | 포트·서비스에서 `Page`·`Pageable`·`PageRequest` 허용 (사용자 결정) | 저장 기술이 아닌 값 묶음이다. delivery도 허용한다 |
| `common.event` 사용 위치 | `adapter/in/event`와 `adapter/out/event` | 지갑 전환 전까지 발행 어댑터가 Spring 이벤트도 함께 보낸다(이중 발행). #343에서 out 허용을 뺀다 |
| 구조 검사 | `ArchitectureTest`를 `freeze` 없이 다시 씀(25개) | 기존 위반이 0건이었다. 위반 코드를 넣는 변이 시험으로 규칙마다 실제로 걸리는지 확인했고, 그때 찾은 우회(새 `adapter.in` 하위 패키지, `JdbcTemplate`·Kafka 클라이언트, `KafkaOperations`, `common.event` 필드 사용, 레이어 밖 패키지 경유)를 막았다 |
| 안 쓰던 저장소 | `PayoutItemRepository` 삭제 | dev에서부터 쓰는 곳이 없었다. 정산 항목은 `Payout` 저장 때 cascade로 저장되고 조회는 `PayoutQueryRepository`가 맡는다 |

## 버린 대안

- **엔티티·도메인 분리:** delivery는 후속(#331~#333)으로 계획했지만 정산은 하지 않는다. 감수하는 것은 domain이 JPA·Hibernate 애노테이션을 아는 것이다. 상태 전이·규칙이 많은 엔티티가 생기면 출력 포트 뒤에서 그 엔티티만 나눈다.
- **배치를 `application`에 두고 규칙 예외:** 이동은 적지만 "서비스는 바깥 기술을 모른다"에 구멍이 생긴다.
- **배치 시작용 출력 포트(`PayoutJobLauncher`):** 계산 없는 서비스 메서드와 인터페이스가 늘고 호출이 한 단계 길어진다.
- **정산 전용 페이지 타입:** 메서드 5개·포트·응답 변환을 다 바꿔야 해 구조 이동 범위를 넘는다.
- **domain 규칙을 허용 목록으로:** 엔티티에 import가 늘 때마다 고쳐야 한다. 금지 목록(바깥 레이어, Spring Data 저장소, JDBC, Kafka, Batch, 웹)으로 둔다.

## 건드릴 때 주의

- 웹 어댑터 경로가 `controller`가 아니어서 `.claude/rules/api.md`가 자동으로 읽히지 않는다. delivery처럼 직접 읽는다. `structure.md`의 레이어 규칙은 정산과 맞지 않는다(문서 반영은 팀 확인 후).
- 저장소 포트를 바꿀 때 쿼리 애노테이션은 `adapter/out/persistence`의 `*JpaRepository`에 있다. 포트에 메서드를 더하고 어댑터에 다시 선언하지 않으면 메서드 이름으로 쿼리가 만들어진다.
- 새 최상위 패키지는 `정산_클래스는_정해진_패키지에만_있다` 규칙에 걸린다. 필요하면 이 목록과 레이어 규칙을 함께 고친다.
- 리스너 패키지가 바뀌어 Spring Modulith 발행 기록(`event_publication`)의 리스너 이름이 달라졌다. 전환 전에 남은 미완료 기록은 새 리스너로 이어지지 않는다(운영 전이라 받아들임).
- (미확인) 포트 타입으로 호출할 때 어댑터에 다시 선언한 `@EntityGraph`가 적용되는지는 쿼리 수를 세는 테스트가 없어 확인하지 않았다. 빠져도 결과는 같고 쿼리 수만 는다.
