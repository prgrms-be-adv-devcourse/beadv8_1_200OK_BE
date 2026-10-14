---
paths:
  - "src/main/java/**/*.java"
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
- 전역 설정(`SecurityConfig` 등)은 `com.ok.config`에 둔다.
- 배치·스케줄러 클래스는 `service` 패키지에 둔다.

**이유**: 성격이 다른 하위 도메인이 한 패키지에 섞이면 `Service`, `Repository` 같은 이름이 겹쳐 구분하기 어렵다. 하위 도메인이 없으면 경로가 단순한 편이 낫고, 설정·리스너를 분리하면 역할별로 찾기 쉽다. 모듈 이름과 하위 도메인 이름이 같은 중복은 헷갈리지 않을 때만 허용한다.

```
// O
com.ok.wallet
├── controller / service / domain / repository
├── exception      에러 코드
├── event          이벤트 리스너
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

- 다른 모듈에 공개할 클래스(서비스, 이벤트)는 **모듈 최상위 패키지**에 둔다. 하위 패키지(`order.internal` 등)는 다른 모듈에서 접근할 수 없다.
- 모듈 간 순환 의존은 금지한다. `common`은 도메인 모듈을 import하지 않는다.
- 모듈 간 연동은 가능하면 이벤트(`@ApplicationModuleListener`)로 한다.
- `package-info.java`는 모듈 선언이므로 지우지 않는다. 새 모듈을 추가하면 `ModulithStructureTest`의 모듈 목록도 갱신한다.

**이유**: 모듈 경계를 지켜야 모듈을 독립적으로 이해하고 바꿀 수 있다. 순환 의존이 있으면 모듈을 따로 테스트하거나 분리할 수 없고, 이벤트로 연동하면 결합이 낮아진다. 위 규칙을 어기면 `ModulithStructureTest`가 실패한다.

```java
// O
package com.ok.member;
public record MemberRegisteredEvent(Long memberId, MemberRole memberRole) { }

// X: 다른 모듈이 쓰는 클래스를 하위 패키지에 둔다
package com.ok.member.internal;
```

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
