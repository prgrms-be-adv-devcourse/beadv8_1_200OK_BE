---
issue: 330
module: delivery
summary: delivery를 포트·어댑터 구조로 옮겨 연동 방식(이벤트에서 브로커·gRPC로)이 바뀌어도 비즈니스 코드가 안 바뀌게 했다. 도메인·엔티티 분리는 후속 PR로 남겼다.
status: 진행 중
date: 2026-10-30
paths: [delivery/src/main/java/com/ok/delivery, delivery/src/test/java/com/ok/delivery/ArchitectureTest.java]
related: [331, 332, 333]
---

> 이 문서는 #330 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR #334가 정확하고, 코드가 바뀌었으면 코드가 우선이다. `paths`의 구조는 PR #334가 머지된 뒤의 경로다. 기록 방식을 보여 주는 첫 예시로, 별도 세션 요약으로 쓴 내용을 이 템플릿에 맞춰 옮겼고 원본의 주장을 코드와 다시 대조하지는 않았다. 대조하지 않았고 W2 전에 확인할 결정(D2')이 남아 있어 `status`를 `진행 중`으로 둔다.

## 배경

모듈별 서버 배포와 브로커·HTTP·gRPC 연동으로 가는 첫 단계로, delivery 모듈을 헥사고날(포트·어댑터) 구조로 바꾼다. 연동 방식(Spring 이벤트에서 브로커·gRPC로)이 바뀌어도 비즈니스 코드가 바뀌지 않게 진입점과 외부 연결을 패키지로 분리하는 것이 목표다.

- 이번 이슈(W1)는 구조만 바꾼다. 모듈 간 연동, 기능, API, DB 스키마, 기존 테스트 의미는 그대로다.
- 범위는 delivery 모듈과 `CLAUDE.md`, `docs/DEVELOPMENT.md`뿐이다. 다른 모듈, `.claude/rules/`, `docs/CONVENTION.md`는 고치지 않는다.
- 도메인·엔티티 분리는 stacked PR 3개(W2 #331, W3 #332, W4 #333)로 나눠 후속으로 한다. 이번에는 W1만 올렸다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 패키지 구조 | `application`(port.in, port.out, service, dto) + `adapter`(in.web, in.event, out.persistence, out.event, out.carrier) | 연동 전환 때 바뀔 코드가 `adapter/*/event`, `adapter/out/carrier`에 모여 영향 범위가 경로로 드러난다. ArchUnit으로 "컨트롤러·리스너는 유스케이스만 안다"를 검사할 수 있다 |
| 웹 어댑터 이름 | `adapter/in/web` (사용자 결정) | 헥사고날 관용 이름이다 |
| 출력 포트 구현 (W1) | `*JpaRepository`가 `JpaRepository`와 포트를 함께 상속 (사용자 결정) | 실행 객체가 지금과 같은 Spring Data 프록시라 잠금·트랜잭션·쿼리가 그대로이고, 위임 클래스를 만들지 않는다. 엔티티가 `domain`에 있는 동안에만 성립한다 |
| 입력 포트 | 서비스마다 `*UseCase` 하나(6개) | `service.md`의 "도메인당 서비스 하나로 시작" 기준과 같고, 컨트롤러가 주입받는 타입 수가 지금과 같다 |
| 페이징 타입 | `Page`, `Pageable`, Spring DAO 예외를 포트·서비스에서 그대로 사용 | 둘 다 JPA가 아닌 Spring 추상화다. 바꾸면 응답 DTO와 서비스 테스트가 함께 바뀌어 "시그니처 조정만"이라는 범위를 넘는다 |
| 택배사 추적 | 리스너가 출력 포트를 직접 부르던 것을 `CarrierTrackingFacade`(트랜잭션 없음)로 감쌈 | 리스너가 port.in만 알아야 한다는 결정 때문이다. `service.md`가 외부 호출을 트랜잭션 없는 Facade에 두라고 한다 |
| `CarrierDeliveredEvent` 위치 | `adapter/in/event` | 택배사가 우리 쪽에 보내는 알림이라 받는 쪽(in) 소유가 맞다. `MockCarrierClient`가 이 이벤트를 쓰는 의존 1건만 ArchUnit에서 예외로 허용한다 |
| ArchitectureTest | delivery 것만 헥사고날 규칙으로 다시 씀(R1~R13) | 문장이 바뀐 규칙은 위반이 0건이라 freeze 없이 바로 강제하고 기준선 파일 3개를 지웠다 |
| 도메인·엔티티 분리 (D3) | **분리한다** (사용자 결정, W2~W4에서 구현) | 서비스를 따로 떼어 낼 때 도메인 규칙이 JPA·`BaseTime`에 묶이지 않게 한다 |
| 변경 반영 (D9) | 더티 체킹 대신 포트의 `save`, 어댑터가 영속 엔티티에 값을 복사 (W2~W4) | 잠금 조회로 얻은 같은 인스턴스를 갱신해서 잠금과 쿼리 수가 그대로다 |

## 버린 대안

- **패키지:** 레이어 구조를 두고 인터페이스만 추가하는 안은 패키지로 경계가 안 보여 ArchUnit으로 검사할 수 없어 버렸다. 하위 도메인(shipment, region, address)별로 먼저 나누는 안은 서로 의존이 생기고 패키지가 세 배로 늘어 버렸다.
- **출력 포트:** 포트마다 어댑터 클래스를 두는 안은 W1에서는 버렸다. D3 때문에 W2부터는 다시 필요해진다. 유스케이스별 세분 포트는 서비스마다 주입 포트가 3~5개로 늘어 버렸다.
- **입력 포트:** 메서드마다 인터페이스를 두는 안(약 20개)은 `service.md`가 금지한 "처음부터 쪼개기"와 같아 버렸다.
- **페이징:** 자체 페이지 타입·예외로 변환하는 안은 MSA 전환에 필요하지 않은 변환 코드와 테스트 변경만 늘어 버렸다.
- **추적 흐름:** `ShipmentService`에 추적 메서드를 넣는 안은 읽기 트랜잭션 안에서 외부 호출이 일어나 경계가 바뀌어 버렸다. 리스너가 출력 포트를 계속 부르는 안은 사용자 결정 위반이다.
- **변경 반영(D9):** `merge`는 cascade가 PERSIST뿐이라 항목 변경이 빠지고 select가 한 번 더 나가 버렸다. 도메인 객체가 엔티티를 숨겨 들고 다니는 안은 D3의 목적과 어긋나 버렸다.

## 건드릴 때 주의

- **W2를 시작하기 전에 D2'를 사용자에게 확인받는다.** 포트가 도메인 모델을, JPA 저장소가 엔티티를 다루게 되면 W1의 상속 방식이 성립하지 않아 영속 어댑터 클래스(`*PersistenceAdapter`)와 매퍼로 바꿔야 한다.
- W2~W4에서 더티 체킹에 맡기던 곳(`DeliveryRegionService.register·update·delete`, `DefaultShippingAddressService.saveDefaultAddress`, `ShipmentService.dispatch·deliver`, `ShipmentAddressChangeService.decide`)에는 명시적 `save`가 필요하다. 빠뜨리면 변경이 조용히 반영되지 않고 단위 테스트에서 안 보이니 경로마다 저장 검증 테스트를 둔다. W4(Shipment aggregate)가 잠금, 지연 로딩, 저장 누락 면에서 가장 위험하다.
- `.claude/rules/`와 `docs/CONVENTION.md`는 일부러 안 고쳤다. delivery는 `docs/DEVELOPMENT.md`의 "delivery 모듈 구조" 절과 delivery `ArchitectureTest`를 따르고, 다른 모듈은 기존 규칙 그대로다. `adapter/in/web`은 `api.md`가 경로로 자동 로드되지 않으므로 직접 읽고 따른다.
- 서비스 메서드의 계약 Javadoc은 입력 포트로 옮겼고 서비스에는 `@Override`만 있다. 잠금 계약(첫 쿼리로 호출, REPEATABLE READ)은 `ShipmentRepository.findByIdForUpdate` Javadoc에 있다.
- `ArchitectureTest`는 `config`·`exception` 등 레이어 밖 패키지를 검사하지 못하고, 서비스끼리 구현 클래스를 직접 호출하는 것(`CarrierTrackingFacade`에서 `ShipmentService`로)은 막지 못한다.
- `JpaRepository`와 포트가 같은 시그니처를 물려줘 호출이 모호해지는 곳은 `@Override`로 다시 선언해 풀었다(`ShipmentJpaRepository.findById`, `RemoteAreaPostalCodeJpaRepository.saveAndFlush`). ArchUnit 패턴 `..domain..`은 `org.springframework.data.domain`에도 맞으므로 `com.ok.delivery.domain..`처럼 완전한 이름으로 쓴다.
- 마이그레이션은 없다. 이번 변경은 스키마를 바꾸지 않는다.
