---
paths:
  - "**/src/main/java/**/*.java"
---

# 패키지·네이밍

클래스와 패키지를 만들 때 따르는 규칙이다. 엔티티는 `entity.md`, 컨트롤러·`*Spec`·요청/응답 DTO는 `api.md`, 서비스 DTO와 `*Command`/`*Query`는 `service.md`를 본다.
사람용 원문: `docs/CONVENTION.md` 2~3절. 모듈 목록은 `docs/DEVELOPMENT.md`를 본다.

## 모듈 내부 구조

`com.ok` 바로 아래 패키지가 하나의 모듈이다.

- 하위 도메인이 없는 모듈: 모듈 바로 아래에 `controller`, `service`, `domain`, `repository`를 둔다.
- 성격이 다른 하위 도메인으로 나뉘는 모듈: `모듈/하위 도메인/레이어` 구조로 둔다.
- 하위 도메인이 하나뿐이면 패키지를 한 단계 더 만들지 않는다(`core` 같은 의미 없는 패키지 금지).
- 필요하면 `exception`(에러 코드), `event`(이벤트 리스너), `config`(모듈 전용 설정)를 추가한다. 하위 도메인이 있으면 그 아래에 둔다.
- 전역 설정(`SecurityConfig` 등)은 `common` 서브프로젝트의 `com.ok.common.config`에 둔다.
- 배치·스케줄러 클래스는 `service` 패키지에 둔다.
- 이벤트 리스너는 서비스만 호출한다. `controller`, `repository`, `domain`을 직접 쓰지 않는다(`ArchitectureTest`가 검사).
- 서비스는 `service` 패키지에 둔 발행 인터페이스로만 이벤트를 발행한다. 인터페이스는 값이나 `*Command`를 받고(이벤트 클래스 금지), 구현체는 `event`에 둔다(`ArchitectureTest`가 검사).
- 발행 인터페이스는 발행하는 도메인(하위 도메인이 있으면 하위 도메인)마다 하나. 이벤트가 늘면 그 인터페이스에 메서드를 추가하고, 서비스마다 쓰는 메서드가 갈리면 유스케이스 기준으로 나눈다. 받는 모듈 기준이나 이벤트마다 나누지 않는다.
- 발행 인터페이스·구현체 이름에 도메인(하위 도메인) 이름을 붙인다(`PayoutEventPublisher`, `SpringPayoutEventPublisher`, 하위 도메인이면 `SellerEventPublisher`, `SpringSellerEventPublisher`).
- 모듈 간 이벤트 DTO는 보내는 모듈과 받는 모듈이 각자 자기 `event` 패키지에 둔다. 공통 클래스를 같이 쓰지 않고, 메시지 모양(필드 이름·타입·단위)으로 약속한다.
- 이벤트 하나를 메시지 브로커로 옮길 때, 그 이벤트의 보내는 쪽과 받는 쪽이 같은 시점에 함께 자기 DTO로 바꾼다. Spring 이벤트는 클래스 타입이 같아야 전달되므로, Spring 이벤트로 주고받는 동안에는 기존 공통 이벤트 클래스(`common.event`)를 쓰고 지금처럼 `DomainEvent`를 구현하고(`ArchitectureTest`가 검사, `common.event.payload` 제외) `event` 패키지에서만 쓴다. 모든 이벤트를 옮기면 `common.event`를 지운다.
- 메시지 약속(토픽, 필드 이름·타입·단위)은 보내는 쪽이 전환 이슈처럼 리뷰어가 볼 수 있는 곳에 적고, 받는 쪽 담당이 그 PR을 함께 리뷰한다.
- 발행 도구(`ApplicationEventPublisher` 등)와 이벤트 DTO는 `event` 패키지에서만 쓴다. 리스너는 이벤트를 `*Command`나 값으로 바꿔 서비스를 호출한다(`ArchitectureTest`가 검사).

**이유**: 성격이 다른 하위 도메인이 한 패키지에 섞이면 `Service`, `Repository` 같은 이름이 겹쳐 구분하기 어렵다. 하위 도메인이 없으면 경로가 단순한 편이 낫고, 설정·리스너를 분리하면 역할별로 찾기 쉽다. 모듈 이름과 하위 도메인 이름이 같은 중복은 헷갈리지 않을 때만 허용한다. 리스너는 컨트롤러처럼 외부 입력의 진입점이므로 트랜잭션과 도메인 처리는 서비스에 맡긴다. 서비스가 발행 인터페이스에만 의존하고 구현체가 그 인터페이스를 구현하면(의존성 역전) 발행 방식을 바꿔도 서비스는 바뀌지 않는다. 인터페이스를 `event`에 두면 리스너와 순환 의존이 생기므로 서비스 쪽에 `public`으로 둔다(모듈 밖 접근은 Modulith가 막는다). 발행 방식이 바뀌어도 인터페이스 개수는 그대로라 하위 패키지를 두지 않는다. 하위 도메인은 역할과 성격이 달라, 한 인터페이스에 모으면 다른 하위 도메인의 서비스가 쓰지 않는 메서드까지 함께 커진다(유스케이스 기준 분리도 같은 이유). 받는 모듈 기준으로 나누면 발행하는 쪽이 받는 쪽을 알게 되고, 이벤트마다 나누면 서비스가 발행 인터페이스를 여러 개 주입받는다. 구현체 이름이 겹치면 빈 이름 충돌로 앱이 시작되지 않는다. 발행 구현체를 `common`에 하나로 두지 않는 이유는 브로커 전환 시 토픽·키·아웃박스가 모듈마다 다르고, 서비스로 분리하면 각 모듈이 구현체를 가져가기 때문이다.

```
// O
com.ok.wallet
├── controller / service / domain / repository   (service에 이벤트 발행 인터페이스)
├── exception      에러 코드
├── event          이벤트 리스너, 발행 인터페이스 구현체
└── config         모듈 전용 설정

// O: 하위 도메인이 있는 경우
com.ok.member
├── member  (controller, service, domain, repository)
└── auth    (controller, service, config)

// X
com.ok.wallet
└── core
    ├── controller
    └── service
```

## 모듈 간 규칙

- 다른 모듈에 공개할 클래스는 **모듈 최상위 패키지**에 둔다. 하위 패키지(`order.internal` 등)는 다른 모듈에서 접근할 수 없다.
- 이벤트 DTO는 공개하지 않는다. 받는 모듈은 보내는 모듈의 클래스를 import하지 않고 자기 DTO를 둔다.
- 모듈 간 순환 의존은 금지한다. `common`은 도메인 모듈을 import하지 않는다.
- 모듈 간 연동은 가능하면 이벤트(`@ApplicationModuleListener`)로 한다.
- 모듈은 Gradle 서브프로젝트다. 도메인 모듈끼리는 `project(...)` 의존을 걸지 않는다(다른 도메인 모듈은 `build.gradle.kts`에 `project(...)`로 선언하지 않으면 컴파일할 수 없다. 선언은 PR diff에서 바로 보이므로 리뷰에서 확인한다). 공용 코드는 `common`에 둔다.
- `package-info.java`는 모듈 선언이므로 지우지 않는다. 새 모듈은 `./gradlew createModule -PmoduleName=<이름>`으로 만든다.

**이유**: 모듈 경계를 지켜야 모듈을 독립적으로 이해하고 바꿀 수 있다. 순환 의존이 있으면 모듈을 따로 테스트하거나 분리할 수 없고, 이벤트로 연동하면 결합이 낮아진다. 도메인 모듈 간 `project(...)` 의존은 선언해야만 컴파일되고 선언은 PR diff에 보이므로, 규칙을 어기는 변경은 리뷰에서 먼저 걸린다.

## `common`에 두는 코드

> 팀 협의 전 초안이다.

- 둘 이상의 모듈이 쓰는 코드(예외, 공통 엔티티 등)만 `common`에 둔다. 한 모듈만 쓰는 코드는 그 모듈에 둔다.
- 여러 모듈이 같은 외부 시스템(S3 등)을 쓰더라도 처음에는 모듈마다 자기 설정을 가져간다.
- 모듈마다 같은 타입의 빈을 만들 때는 빈 이름에 모듈 이름을 붙인다(`mediaS3Client`). 같은 이름이면 앱이 시작되지 않는다.

**이유**: `common`이 커지면 모듈 경계가 흐려지고 한 모듈의 변경이 `common`을 거쳐 다른 모듈로 퍼진다. 아직 필요하지 않은 공통 코드는 미리 만들지 않는다.

## 역할별 접미사

| 역할 | 접미사 | 예 |
|---|---|---|
| 컨트롤러 | `*Controller` | `MediaController` |
| 서비스 | `*Service` | `WalletService` |
| 저장소 | `*Repository` | `MemberRepository` |
| 조회 전용 저장소 | `*QueryRepository` | `PayoutQueryRepository` |
| 요청 / 응답 DTO | `*Request` / `*Response` | `SignupRequest` |
| 서비스 입력 DTO (쓰기) | `*Command` | `SignupCommand` |
| 서비스 입력 DTO (읽기 조건) | `*Query` | `ProductSearchQuery` |
| 서비스 결과 DTO | `*Result` | `MediaUploadResult` |
| 이벤트 (과거형 동사) | `*Event` | `MemberRegisteredEvent` |
| 이벤트 리스너 | `*EventListener` | `MemberEventListener` |
| 에러 코드 | `*ErrorCode` | `MemberErrorCode` |
| 설정 / 정책 / 검증기 | `*Config` / `*Policy` / `*Validator` | `PayoutPolicy` |
| 외부 호출 조율 | `*Facade` | `MediaUploadFacade` |
| Swagger 문서용 인터페이스 | `*Spec` | `MediaControllerSpec` |

- 응답 DTO는 `*Response`로 통일한다. `*Info`, `*Result` 같은 다른 접미사를 응답에 쓰지 않는다.
- 엔티티는 접미사 없이 도메인 이름을 쓴다(`Member`, `Cart`). enum 이름은 도메인에 맞게 자유롭게 짓는다.
- 세부 이름은 자유롭게 짓되 같은 모듈 안에서 일관성을 유지한다.

**이유**: 접미사만 보고 역할과 위치를 알 수 있어야 코드를 찾고 읽기 쉽다.

```java
// X
class WalletManager { ... }                  // Service 역할
record SignupResult(Long memberId) { ... }   // Response 역할
```
