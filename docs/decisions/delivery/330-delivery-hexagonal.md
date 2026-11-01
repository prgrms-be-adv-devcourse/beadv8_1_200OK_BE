---
issue: 330
pr: 334
module: delivery
modules: [delivery]
title: delivery 모듈 헥사고날 구조 변경 (W1 구조 이동)
summary: delivery를 포트·어댑터 구조로 옮겨 연동 방식(이벤트에서 브로커·gRPC로)이 바뀌어도 비즈니스 코드가 안 바뀌게 했다. 도메인·엔티티 분리는 후속 PR로 남겼다.
status: 진행 중
date: 2026-10-30
paths: [delivery/src/main/java/com/ok/delivery, delivery/src/test/java/com/ok/delivery/ArchitectureTest.java]
related: [#331, #332, #333]
---

> 이 문서는 #330 작업의 설계 의도와 결정의 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR #334가 정확하고, 코드가 바뀌었으면 코드가 우선이다. 아래 수치(줄 수, 테스트 건수, 규칙 개수)는 PR #334의 마지막 커밋 `a624c86` 기준이다. 이 레포는 squash merge를 쓰므로 머지 뒤에는 이 해시가 이력에 남지 않는다. `paths`의 구조는 PR #334가 머지된 뒤의 경로다.
> 진행 상태(머지 여부, 하위 이슈 진행)는 PR #334와 이슈 #331~#333이 정확하다. 이 문서는 기록 방식을 보여 주는 첫 예시이며, 원래 별도 세션 요약으로 쓴 내용을 이 템플릿에 맞춰 옮긴 것이다.

## 목적과 범위

모듈별 서버 배포와 브로커·HTTP·gRPC 연동으로 가는 첫 단계로, delivery 모듈을 헥사고날(포트·어댑터) 구조로 바꾼다. 연동 방식이 바뀌어도 비즈니스 코드가 바뀌지 않게 진입점과 외부 연결을 패키지로 분리하는 것이 목표다.

- 이번 이슈는 구조만 바꾼다. 모듈 간 연동(Spring 이벤트, `/internal` 호출), 기능, API, DB 스키마, 기존 테스트 의미는 그대로다.
- 범위는 delivery 모듈과 `CLAUDE.md`, `docs/DEVELOPMENT.md`뿐이다. 다른 모듈, `.claude/rules/`, `docs/CONVENTION.md`는 고치지 않는다.
- 작업은 stacked PR 4개로 나눴고 이번에는 W1만 올렸다(아래 "남은 작업").

## 핵심 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 패키지 구조 | `application`(port.in, port.out, service, dto) + `adapter`(in.web, in.event, out.persistence, out.event, out.carrier) | 연동 전환 때 바뀔 코드가 `adapter/*/event`, `adapter/out/carrier`에 모여 영향 범위가 경로로 드러난다. ArchUnit으로 "컨트롤러·리스너는 유스케이스만 안다"를 검사할 수 있다 |
| 웹 어댑터 이름 | `adapter/in/web` (사용자 결정) | 헥사고날 관용 이름. 대신 `.claude/rules/api.md`가 경로(`**/controller/**`)로 자동 로드되지 않아 `CLAUDE.md`에 수동 안내를 넣었다 |
| 출력 포트 구현 (W1) | `*JpaRepository`가 `JpaRepository`와 포트를 함께 상속 (사용자 결정) | 실행 객체가 지금과 같은 Spring Data 프록시라 잠금·트랜잭션·쿼리가 그대로이고, 위임 클래스를 만들지 않는다 |
| 입력 포트 | 서비스마다 `*UseCase` 하나(6개) | `service.md`의 "도메인당 서비스 하나로 시작" 기준과 같다. 컨트롤러가 주입받는 타입 수가 지금과 같다 |
| 페이징 타입 | `Page`, `Pageable`, Spring DAO 예외를 포트·서비스에서 그대로 사용 | 둘 다 JPA가 아닌 Spring 추상화다. 바꾸면 응답 DTO와 서비스 테스트가 함께 바뀌어 "시그니처 조정만"이라는 범위를 넘는다 |
| 택배사 추적 | 리스너가 출력 포트를 직접 부르던 것을 `CarrierTrackingFacade`(트랜잭션 없음)로 감쌈 | 리스너가 port.in만 알아야 한다는 결정 때문. `service.md`가 외부 호출을 트랜잭션 없는 Facade에 두라고 한다 |
| `CarrierDeliveredEvent` 위치 | `adapter/in/event` | 택배사가 우리 쪽에 보내는 알림이라 받는 쪽(in) 소유가 맞다. `MockCarrierClient`가 이 이벤트를 쓰는 의존 1건만 ArchUnit에서 예외로 허용 |
| ArchitectureTest | delivery 것만 헥사고날 규칙으로 다시 씀(R1~R13) | 문장이 바뀐 규칙은 위반이 0건이라 freeze 없이 바로 강제하고 기준선 파일 3개를 지웠다 |
| 도메인·엔티티 분리 (D3) | **분리한다** (사용자 결정, W2~W4에서 구현) | 서비스를 따로 떼어 낼 때 도메인 규칙이 JPA·`BaseTime`에 묶이지 않게 한다 |
| 변경 반영 (D9) | 더티 체킹 대신 포트의 `save`, 어댑터가 영속 엔티티에 값을 복사 (W2~W4) | 잠금 조회로 얻은 같은 인스턴스를 갱신해서 잠금과 쿼리 수가 그대로다 |

## 검토한 대안과 버린 이유

- **패키지:** 레이어 구조를 두고 인터페이스만 추가하는 안은 패키지로 경계가 안 보여 ArchUnit으로 검사할 수 없어 버렸다. 하위 도메인(shipment, region, address)별로 먼저 나누는 안은 서로 의존이 생기고 패키지가 세 배로 늘어 버렸다.
- **출력 포트:** 포트마다 어댑터 클래스를 두는 안은 W1에서는 버렸고, D3 때문에 W2부터 다시 필요해진다(D2'). 유스케이스별 세분 포트는 서비스마다 주입 포트가 3~5개로 늘어 버렸다.
- **입력 포트:** 메서드마다 인터페이스를 두는 안(당시 추정 약 20개)은 `service.md`가 금지한 "처음부터 쪼개기"와 같아 버렸다.
- **페이징:** 자체 페이지 타입·예외로 변환하는 안은 MSA 전환에 필요하지 않은 변환 코드와 테스트 변경만 늘어 버렸다.
- **추적 흐름:** `ShipmentService`에 추적 메서드를 넣는 안은 읽기 트랜잭션 안에서 외부 호출이 일어나 경계가 바뀌어 버렸다. 리스너가 출력 포트를 계속 부르는 안은 사용자 결정 위반이다.
- **변경 반영(D9):** `merge`는 cascade가 PERSIST뿐이라 항목 변경이 빠지고 select가 한 번 더 나가 버렸다. 도메인 객체가 엔티티를 숨겨 들고 다니는 안은 D3의 목적과 어긋나 버렸다.

## 설계 의도

- **포트·어댑터 분리:** 연동 방식(Spring 이벤트에서 브로커·gRPC로)이 바뀔 때 `adapter` 쪽만 고치도록 경계를 코드로 만든다.
- **Spring Data 저장소의 포트 상속:** 위임만 하는 어댑터 클래스(당시 추정 약 300줄)와 위임 실수(잠금 메서드를 일반 조회로 연결 등)를 피한다. 이 방식은 엔티티가 `domain`에 있는 동안에만 성립한다.
- **ArchUnit:** 구조는 사람이 지켜서는 무너진다. 규칙마다 일부러 위반을 넣어 실패하는 것을 확인했다.
- **Spring 이벤트 유지:** 이번 이슈 범위가 아니다. 브로커 전환은 이후 이슈다.

## 구현 중 부딪힌 것

- `JpaRepository`와 포트가 같은 시그니처를 물려줘 호출이 모호해졌다. `ShipmentJpaRepository.findById`, `RemoteAreaPostalCodeJpaRepository.saveAndFlush`를 `@Override`로 다시 선언해서 풀었다.
- ArchUnit 패턴 `..domain..`이 `org.springframework.data.domain`(Page)에도 맞아 오탐이 났다. 완전한 패키지 이름(`com.ok.delivery.domain..`)으로 적었다.
- 이벤트 클래스 규칙을 `adapter..`만 허용하면 `common.event` 안의 상호 참조가 위반으로 잡혀서, `common.event` 자기 패키지도 허용했다.

## 남은 과제와 주의할 점

**남은 작업 (stacked PR)**

- W2 #331 지역 aggregate, W3 #332 판매자·회원 설정, W4 #333 Shipment aggregate의 도메인·엔티티 분리. 순서는 W1 ← W2 ← W3 ← W4다.
- **W2 시작 전에 D2'를 사용자에게 확인받아야 한다.** 포트가 도메인 모델을, JPA 저장소가 엔티티를 다루게 되면 W1의 상속 방식이 성립하지 않아, 영속 어댑터 클래스(`*PersistenceAdapter`)와 매퍼로 바꿔야 한다.
- W4가 가장 위험하다(잠금, 지연 로딩, 저장 누락). 당시 추정으로 운영 코드 약 900줄이지만 Shipment, Item, ChangeRequest, Address가 맞물려 더 못 쪼갠다.
- W2~W4에서 더티 체킹에 맡기던 곳(`DeliveryRegionService.register·update·delete`, `DefaultShippingAddressService.saveDefaultAddress`, `ShipmentService.dispatch·deliver`, `ShipmentAddressChangeService.decide`)에 명시적 `save`를 넣어야 한다. 빠뜨리면 변경이 조용히 반영되지 않고 단위 테스트에서 안 보이니, 경로마다 저장 검증 테스트를 추가한다.

**알려진 약점 (코드 평가에서 반복 지적된 것)**

- 포트에 `Page`/`Pageable`, `<S extends T> save`, `saveAndFlush`, 파생 쿼리 메서드 이름이 노출된다. 의도적 허용(D5)이지만 순수 포트는 아니다.
- `MockCarrierClient`가 `adapter.in`의 `CarrierDeliveredEvent`를 쓰는 예외가 ArchUnit에 있다. 실제 택배사 연동이 생기면 사라진다.
- `ArchitectureTest`는 `config`·`exception` 등 레이어 밖 패키지를 검사하지 못하고, 서비스끼리 구현 클래스를 직접 호출하는 것(`CarrierTrackingFacade`에서 `ShipmentService`로)은 막지 못한다.
- 위임만 하는 메서드가 있다(`CarrierTrackingFacade.requestTracking`, `DefaultShippingAddressFacade.getDefaultAddress`).
- `DeliveryEventPublisher` Javadoc이 구현 위치를 `event` 패키지라고 적고 있어 낡았다(실제는 `adapter/out/event`).
- `ShipmentJpaRepository.existsByCarrierAndTrackingNumber`는 호출하는 곳이 없다(변경 전부터 있던 코드).

**건드릴 때 주의**

- `.claude/rules/`와 `docs/CONVENTION.md`는 일부러 안 고쳤다. delivery는 `docs/DEVELOPMENT.md`의 "delivery 모듈 구조" 절과 delivery `ArchitectureTest`를 따르고, 다른 모듈은 기존 규칙 그대로다.
- `adapter/in/web`은 `api.md`가 자동 로드되지 않으므로 직접 읽고 따른다.
- 서비스 메서드의 계약 Javadoc은 입력 포트로 옮겼고 서비스에는 `@Override`만 있다. 잠금 계약(첫 쿼리로 호출, REPEATABLE READ)은 `ShipmentRepository.findByIdForUpdate` Javadoc에 있다.
- 마이그레이션은 없다. 이번 변경은 스키마를 바꾸지 않는다.

## 검증

- 확인한 것: delivery 테스트 502에서 510건으로 늘었고 실패 0, monolith 60건, 전체 `./gradlew clean test` 2065건 실패 0. 기존 테스트를 지우지 않았고 이름 변경 1건, 이동 2건뿐이다. `ArchitectureTest`는 17개에서 21개로 늘었다(9개 삭제, 13개 추가). 개발자가 규칙 11개가 실제 위반을 잡는 것을 확인했다. 모듈 경계는 `delivery/build.gradle.kts` 무변경, `common`만 의존, delivery 소스의 `com.ok.*` import는 delivery·common뿐이다. 앱(monolith)은 기동을 확인했다.
- 확인하지 못한 것: `MockCarrierClient`가 배송 완료까지 이어지는 시연 경로.

## 이어서 작업할 때

- 시작: `/feature-team:team-feature <하위 이슈 번호>`. 하위 이슈 본문에 상위 이슈와 의존 순서가 적혀 있다.
- 이 작업의 설계 문서와 보고서는 저장소 밖에 있어 사라질 수 있다. 결정의 이유는 이 문서를 기준으로 한다.
- 팀으로 할지 단일 에이전트로 할지는 규모와 위험도로 정한다. W1은 확정된 설계의 구조 이동이라 단일 에이전트로도 충분했을 가능성이 크다. W2~W4는 저장 누락 같은 조용한 실패가 있어 독립 검증이 값을 할 수 있다.
