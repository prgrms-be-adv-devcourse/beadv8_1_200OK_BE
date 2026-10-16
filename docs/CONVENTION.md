# 코드 컨벤션

paldo-gotgan 프로젝트에서 기능을 구현할 때 팀이 지키는 기준을 정리한 문서이다.

## 목차

- [1. 이 문서에 대해](#1-이-문서에-대해)
  - [무엇을 다루는가](#무엇을-다루는가)
  - [읽는 사람](#읽는-사람)
- [2. 패키지·모듈 구조](#2-패키지모듈-구조)
  - [2.1 모듈 내부 구조](#21-모듈-내부-구조)
  - [2.2 모듈 간 규칙](#22-모듈-간-규칙)
  - [2.3 DTO 위치](#23-dto-위치)
  - [2.4 `common`에 두는 코드](#24-common에-두는-코드)
- [3. 네이밍](#3-네이밍)
  - [3.1 기본 원칙](#31-기본-원칙)
  - [3.2 역할별 접미사](#32-역할별-접미사)
  - [3.3 Swagger 문서용 인터페이스](#33-swagger-문서용-인터페이스)
  - [3.4 컨트롤러 이름](#34-컨트롤러-이름)
  - [3.5 쓰기와 읽기 구분 (CQRS-lite)](#35-쓰기와-읽기-구분-cqrs-lite)
- [4. 엔티티](#4-엔티티)
  - [4.1 공통 부모](#41-공통-부모)
  - [4.2 삭제 방식](#42-삭제-방식)
- [5. DTO·API](#5-dtoapi)
  - [5.1 URL](#51-url)
  - [5.2 멱등성](#52-멱등성)
  - [5.3 Swagger 문서화](#53-swagger-문서화)
  - [5.4 값 정규화·변환](#54-값-정규화변환)
- [6. 예외 처리](#6-예외-처리)
  - [6.1 예외 종류](#61-예외-종류)
  - [6.2 에러 코드](#62-에러-코드)
  - [6.3 에러 응답의 `type` 주소(slug)](#63-에러-응답의-type-주소slug)
  - [6.4 에러 코드의 `title`](#64-에러-코드의-title)
  - [6.5 에러 코드 작성](#65-에러-코드-작성)
  - [6.6 검증 위치](#66-검증-위치)
- [7. 서비스·트랜잭션](#7-서비스트랜잭션)
  - [7.1 트랜잭션 선언](#71-트랜잭션-선언)
  - [7.2 외부 호출과 Facade](#72-외부-호출과-facade)
  - [7.3 현재 시각](#73-현재-시각)
  - [7.4 로그](#74-로그)
  - [7.5 서비스를 나누는 기준](#75-서비스를-나누는-기준)
- [8. DB 마이그레이션 (Flyway)](#8-db-마이그레이션-flyway)
  - [8.1 파일 작성](#81-파일-작성)
  - [8.2 적용된 파일과 병합 순서](#82-적용된-파일과-병합-순서)
  - [8.3 테이블 옵션](#83-테이블-옵션)
  - [8.4 제약·인덱스 이름](#84-제약인덱스-이름)
  - [8.5 테이블 이름](#85-테이블-이름)
  - [8.6 외래키](#86-외래키)
  - [8.7 컬럼 주석(`COMMENT`)](#87-컬럼-주석comment)
  - [8.8 SQL 포맷](#88-sql-포맷)
- [9. 테스트](#9-테스트)
  - [9.0 작성 순서](#90-작성-순서)
  - [9.1 테스트 메서드 이름](#91-테스트-메서드-이름)
  - [9.2 본문 구역](#92-본문-구역)
  - [9.3 검증 대상 변수명 `sut`](#93-검증-대상-변수명-sut)
  - [9.4 테스트 종류와 사용 기준](#94-테스트-종류와-사용-기준)
  - [9.5 파라미터화 테스트](#95-파라미터화-테스트)
  - [9.6 여러 필드 검증](#96-여러-필드-검증)
  - [9.7 예외 검증](#97-예외-검증)
  - [9.8 `RestApiException`의 에러 코드 확인](#98-restapiexception의-에러-코드-확인)
  - [9.9 테스트 클래스](#99-테스트-클래스)
  - [9.10 서비스 단위 테스트 작성 방침](#910-서비스-단위-테스트-작성-방침)
  - [9.11 DTO 테스트](#911-dto-테스트)
  - [9.12 외부 시스템 경계](#912-외부-시스템-경계)
- [10. 코드 포맷](#10-코드-포맷)
  - [10.1 import](#101-import)
  - [10.2 파일 끝](#102-파일-끝)
  - [10.3 빈 줄](#103-빈-줄)
  - [10.4 주석](#104-주석)
  - [10.5 TODO](#105-todo)
  - [10.6 Javadoc 작성 방법](#106-javadoc-작성-방법)
- [11. Git·협업](#11-git협업)
  - [11.1 커밋 메시지](#111-커밋-메시지)
  - [11.2 브랜치 전략](#112-브랜치-전략)
  - [11.3 PR과 이슈](#113-pr과-이슈)
- [머지 전 확정할 항목](#머지-전-확정할-항목)
  - [엔티티 (4절의 나머지는 팀 협의 후 작성)](#엔티티-4절의-나머지는-팀-협의-후-작성)
- [향후 개선 사항](#향후-개선-사항)
  - [통합 테스트의 데이터 정리 방식](#통합-테스트의-데이터-정리-방식)
  - [서비스의 읽기·쓰기 분리](#서비스의-읽기쓰기-분리)

## 1. 이 문서에 대해

### 무엇을 다루는가

기능을 구현할 때 따르는 기준이다.

- 패키지·모듈 구조, 클래스·변수 이름
- 엔티티, DTO·API, 예외, 서비스·트랜잭션을 작성하는 방식
- DB 마이그레이션 작성 규칙
- 테스트 코드 작성 기준
- 코드 포맷(import, 주석)
- 브랜치, 커밋, PR 같은 팀 협업 규칙

프로젝트를 시작하고 실행하는 방법(기술 스택, 환경변수, Docker 등)은 [`DEVELOPMENT.md`](DEVELOPMENT.md)에 있다. 두 문서의 역할 분담도 그 문서 첫머리에 적혀 있다.

문서 끝의 "머지 전 확정할 항목"은 팀 협의가 끝나면 지우는 임시 절이고, "향후 개선 사항"은 보류한 개선 항목을 모은 절이다.

### 읽는 사람

팀원과 AI 코딩 도구 모두를 대상으로 한다. AI가 따를 수 있도록 각 규칙을 다음 순서로 쓴다.

1. 규칙: 명확한 한 문장
2. 이유: 왜 그렇게 하는가
3. 좋은 예와 나쁜 예: 실제 코드나 이름

## 2. 패키지·모듈 구조

`com.ok` 바로 아래 패키지가 하나의 모듈이다. 모듈 목록과 설명은 [`DEVELOPMENT.md`](DEVELOPMENT.md)의 모듈 구조를 본다.

### 2.1 모듈 내부 구조

**규칙**
- 하위 도메인이 없는 모듈은 모듈 바로 아래에 레이어 패키지(`controller`, `service`, `domain`, `repository`)를 둔다.
- 도메인이 커서 성격이 다른 하위 도메인으로 나뉘는 모듈은 `모듈/하위 도메인/레이어` 구조로 둔다.
- 하위 도메인이 하나뿐인 모듈은 패키지를 한 단계 더 만들지 않는다. 의미 없는 패키지를 두지 않는다.
- 필요하면 `exception`(에러 코드), `event`(이벤트 리스너), `config`(모듈 전용 설정) 패키지를 추가한다. 하위 도메인이 있는 모듈은 이 패키지도 하위 도메인 아래에 둔다. 전역 설정(`SecurityConfig` 등)은 `com.ok.config` 모듈에 둔다.
- 배치·스케줄러 클래스는 `service` 패키지에 둔다.
- 이벤트 리스너는 서비스만 호출한다. `controller`, `repository`, `domain`을 직접 쓰지 않는다.
- 서비스는 `service/publisher`의 인터페이스로만 이벤트를 발행한다. 인터페이스는 이벤트 클래스가 아니라 값이나 `*Command`를 받고, 구현체는 `event` 패키지에 둔다.
- `ApplicationEventPublisher`와 이벤트 클래스(`common.event`)는 `event` 패키지에서만 쓴다. 리스너는 이벤트를 `*Command`나 값으로 바꿔 서비스를 호출한다.

**이유**: 
한 모듈에 성격이 다른 하위 도메인이 있으면(예: product와 review) `Service`, `Repository` 같은 클래스 이름이 겹치고, 이름만으로 구분하기 어렵다. 
모듈 이름과 하위 도메인 이름이 구분되어 헷갈리지 않는다면, 두 이름이 같은 네이밍 중복은 허용한다. 
하위 도메인이 없는 모듈은 경로가 단순한 편이 낫다. 
설정과 리스너를 서비스·도메인과 구분하면 역할별로 코드를 찾기 쉽다. 
리스너는 컨트롤러처럼 외부 입력이 들어오는 진입점이므로, 트랜잭션과 도메인 처리는 서비스에 맡긴다. 
서비스가 발행 인터페이스에만 의존하면 발행 방식(스프링 이벤트, 메시지 브로커, 아웃박스)을 바꿔도 서비스는 바뀌지 않고, 모듈 간 계약 타입이 비즈니스 코드에 섞이지 않는다. 인터페이스를 `event`에 두면 리스너(event → service)와 순환 의존이 생기므로 서비스 쪽에 두고, 다른 패키지의 구현체가 구현해야 하므로 `public`으로 둔다. 모듈 밖에서는 Modulith가 접근을 막는다. 


```
// 좋은 예: 하위 도메인이 없는 모듈
com.ok.wallet
├── controller
├── service
│   └── publisher  이벤트 발행 인터페이스
├── domain
├── repository
├── exception      에러 코드
├── event          이벤트 리스너, 발행 인터페이스 구현체
└── config         모듈 전용 설정

// 좋은 예: 하위 도메인이 있는 모듈 (member 모듈의 회원과 인증)
com.ok.member
├── member
│   ├── controller
│   ├── service
│   ├── domain
│   └── repository
└── auth
    ├── controller
    ├── service
    └── config

// 나쁜 예: 하위 도메인이 하나뿐인데 의미 없는 패키지를 하나 더 만든다
com.ok.wallet
└── core
    ├── controller
    └── service
```

### 2.2 모듈 간 규칙

**규칙**
- 다른 모듈에 공개할 클래스(서비스, 이벤트)는 **모듈의 최상위 패키지**에 둔다. 하위 패키지(예: `order.internal`)는 다른 모듈에서 접근할 수 없다.
- 모듈 간 순환 의존은 금지한다. `common`은 도메인 모듈을 import하지 않는다.
- 모듈 간 연동은 가능하면 이벤트(`@ApplicationModuleListener`)로 한다.
- 각 모듈 패키지의 `package-info.java`는 모듈 선언이므로 지우지 않는다. 새 모듈을 추가하면 `ModulithStructureTest`의 모듈 목록도 갱신한다. 위 규칙을 어기면 이 테스트가 실패한다.

**이유**: 모듈 경계를 지켜야 모듈을 독립적으로 이해하고 바꿀 수 있다. 순환 의존이 있으면 모듈을 따로 테스트하거나 분리할 수 없고, 이벤트로 연동하면 모듈 간 결합이 낮아진다.

```java
// 좋은 예: 다른 모듈에 공개하는 이벤트는 모듈 최상위 패키지에 둔다
package com.ok.member;

public record MemberRegisteredEvent(Long memberId, MemberRole memberRole) { }

// 나쁜 예: 다른 모듈이 쓰는 클래스를 하위 패키지에 둔다 (다른 모듈에서 접근할 수 없다)
package com.ok.member.internal;

public record MemberRegisteredEvent(Long memberId, MemberRole memberRole) { }
```

### 2.3 DTO 위치

**규칙**
- 요청·응답 DTO(`*Request`, `*Response`)는 `controller/dto`에 둔다.
- `*Command`, `*Query`, `*Result`는 `service/dto`에 둔다.
- DTO를 컨트롤러나 서비스 클래스 안의 중첩 `record`로 두지 않는다.
- 서비스는 `*Response`를 반환하지 않는다. 컨트롤러에는 식별자나 서비스 결과 DTO `*Result`를 반환하고, 컨트롤러가 `*Result`로 `*Response`를 만든다.
- 컨트롤러(`controller/dto` 포함)는 `domain` 패키지의 타입(엔티티, enum 등)을 쓰지 않는다.

**이유**: 컨트롤러 계층과 서비스 계층의 입출력 타입을 패키지로 나누면, 어느 계층이 쓰는 타입인지 경로만 보고 알 수 있다. 중첩 `record`는 파일을 키우고 다른 곳에서 찾기 어렵다. 웹 응답 타입의 책임은 프레젠테이션 레이어에 있다. 프레젠테이션과 영속성은 바뀔 수 있어도 비즈니스(서비스)는 그 영향을 받지 않아야 하므로, 서비스가 컨트롤러의 타입(`*Response`)을 알면 안 된다. 반대로 컨트롤러가 엔티티를 알면 엔티티 변경이 API 응답까지 번지고, 지연 로딩 필드를 트랜잭션 밖에서 읽게 된다.

```
// 좋은 예
com.ok.member
├── controller
│   └── dto        SignupRequest, SignupResponse
└── service
    └── dto        SignupCommand
```

```java
// 나쁜 예: 컨트롤러 안에 응답 record를 둔다
public class PayoutController {
    public record PayoutRunResponse(Long executionId, String status) { }
}
```

```java
// 좋은 예: 서비스는 식별자를 반환하고, 컨트롤러가 응답 DTO를 만든다
Long memberId = memberSignupService.signup(command);                          // 컨트롤러에서 서비스 호출
return ResponseEntity.status(HttpStatus.CREATED).body(new SignupResponse(memberId));

// 나쁜 예: 서비스가 응답 DTO를 반환한다 (비즈니스가 컨트롤러의 타입을 알게 된다)
public MediaResponse upload(MediaPurpose purpose, MultipartFile file) { ... }  // 서비스
```

```java
// 좋은 예: 묶어서 넘길 값은 서비스가 *Result로 만들고, 컨트롤러가 *Response로 바꾼다
public CartResult getMyCart(Long memberId) { ... }                             // 서비스
return ResponseEntity.ok(CartResponse.from(cartService.getMyCart(memberId)));  // 컨트롤러

// 나쁜 예: 컨트롤러 쪽 DTO가 엔티티를 받는다 (엔티티 변경이 API 응답까지 번진다)
public static CartResponse of(Cart cart, List<CartItem> items) { ... }
```

### 2.4 `common`에 두는 코드

> 팀 협의 전 초안이다. 하단의 "머지 전 확정할 항목"을 확인한다.

**규칙**
- `common`에는 둘 이상의 모듈이 쓰는 코드(예외, 공통 엔티티 등)만 둔다. 한 모듈만 쓰는 코드는 그 모듈에 둔다.
- 여러 모듈이 같은 외부 시스템(S3 등)을 쓰더라도 처음에는 모듈마다 자기 설정을 가져간다. 공통으로 묶을 규칙이 생기면 그때 `common`으로 옮긴다.
- 모듈마다 같은 타입의 빈을 만들 때는 빈 이름에 모듈 이름을 붙여 겹치지 않게 한다(예: `mediaS3Client`).

**이유**: `common`이 커지면 모듈 경계가 흐려지고, 한 모듈의 변경이 `common`을 거쳐 다른 모듈에 퍼질 수 있다. 아직 필요하지 않은 공통 코드를 미리 만들지 않는다. 같은 이름의 빈이 둘이면 스프링이 빈 이름 충돌로 앱을 시작하지 못한다.

```
// 좋은 예: 여러 모듈이 쓰는 코드
common.exception.RestApiException      (media, member, order, payout이 사용)
common.jpa.entity.BaseIdAndTime        (member, order, payout, wallet이 사용)

// 나쁜 예: 한 모듈만 쓰는 코드를 common에 둔다
common.media.dto.MediaInfo             (media만 사용)
common.aws.S3Config                    (S3Client를 쓰는 것은 media뿐)
```

## 3. 네이밍

### 3.1 기본 원칙

**규칙**: 세부 이름은 자유롭게 짓되, 같은 모듈 안에서는 일관성을 유지한다. 역할이 정해진 클래스는 3.2의 접미사를 따른다.

**이유**: 이름을 지나치게 세세하게 정하지 않으면서, 클래스 이름만 보고 역할을 알 수 있게 한다.

### 3.2 역할별 접미사

**규칙**: 역할이 정해진 클래스는 아래 접미사를 붙인다.

| 역할 | 접미사 | 예 |
|---|---|---|
| 컨트롤러 | `*Controller` | `MediaController` |
| 서비스 | `*Service` | `WalletService` |
| 저장소 | `*Repository` | `MemberRepository` |
| 요청 DTO | `*Request` | `SignupRequest` |
| 응답 DTO | `*Response` | `SignupResponse` |
| 서비스 입력 DTO (쓰기) | `*Command` | `SignupCommand` |
| 서비스 입력 DTO (읽기 조건) | `*Query` | `ProductSearchQuery` (예시) |
| 서비스 결과 DTO | `*Result` | `MediaUploadResult` (예시) |
| 조회 전용 저장소 | `*QueryRepository` | `PayoutQueryRepository` |
| 이벤트 | `*Event` (과거형 동사) | `MemberRegisteredEvent` |
| 이벤트 리스너 | `*EventListener` | `MemberEventListener` |
| 에러 코드 | `*ErrorCode` | `MemberErrorCode` |
| 설정 | `*Config` | `SecurityConfig` |
| 정책 | `*Policy` | `PayoutPolicy` |
| 검증기 | `*Validator` | `MemberDuplicateValidator` |
| 외부 호출 조율 | `*Facade` (7.2 참고) | `MediaUploadFacade` (예시) |
| Swagger 문서용 인터페이스 | `*Spec` (3.3 참고) | `MediaControllerSpec` |

응답 DTO는 `*Response`로 통일하고, `*Info` 같은 다른 접미사를 쓰지 않는다. 엔티티는 접미사 없이 도메인 이름을 그대로 쓴다(`Member`, `Cart`, `Wallet`). 상태, 유형 같은 enum 이름은 도메인에 맞게 자유롭게 짓는다.

**이유**: 접미사만 보고 역할과 위치를 알 수 있으면 코드를 찾고 읽기 쉽다.

```java
// 좋은 예
class WalletService { ... }
record SignupResponse(Long memberId) { ... }

// 나쁜 예: 역할이 이름에서 드러나지 않거나, 정해진 접미사를 쓰지 않는다
class WalletManager { ... }      // Service 역할
record SignupResult(Long memberId) { ... }   // Response 역할
```

### 3.3 Swagger 문서용 인터페이스

**규칙**: Swagger 문서 어노테이션만 모아 둔 인터페이스는 **대상 클래스 이름 + `Spec`** 으로 짓고, **package-private**으로 대상과 **같은 패키지**에 둔다. 별도 `spec` 패키지는 만들지 않고, `sealed`로 구현을 강제하지 않는다.

**이유**: 문서 어노테이션을 컨트롤러 코드와 분리하되, 다른 곳에서는 쓰이지 않으므로 package-private으로 노출하지 않는다. `sealed`는 인터페이스가 구현체 이름을 알아야 해서 문서가 구현을 가리키는 역방향 의존이 생기고, 구현 클래스가 `final`이어야 하는 제약이 따라온다.

```java
// 좋은 예: 대상 이름 + Spec, package-private, 컨트롤러와 같은 패키지
@Tag(name = "Media", description = "미디어 업로드 API")
interface MediaControllerSpec {

    @Operation(summary = "미디어 업로드")
    ResponseEntity<MediaResponse> upload(String purpose, MultipartFile file);
}

public class MediaController implements MediaControllerSpec { ... }

// 나쁜 예: Docs 접미사, public, 별도 spec 패키지
public interface MemberControllerDocs { ... }
com.ok.media.controller.spec.MediaApiSpec
```

### 3.4 컨트롤러 이름

**규칙**: 컨트롤러 이름에 API 버전 접두사(`ApiV1` 등)를 붙이지 않는다. 버전은 URL(`/api/v1/...`)에만 둔다.

**이유**: 버전은 URL이 이미 담고 있어서 이름에 또 적으면 중복이다. 접두사가 없어야 컨트롤러 이름이 3.2의 `*Controller` 규칙과 같고, 3.3의 `*Spec` 이름도 짧아진다.

```java
// 좋은 예
@RestController
@RequestMapping("/api/v1/members")
class MemberController { ... }

// 나쁜 예: 버전이 이름에도 들어간다
class ApiV1MemberController { ... }
```

### 3.5 쓰기와 읽기 구분 (CQRS-lite)

**규칙**
- 상태를 바꾸는 유스케이스(쓰기)의 입력은 `*Command`, 조회(읽기)의 조건은 `*Query`로 이름 붙인다.
- 조회만 하는 저장소(Querydsl 조회 등)는 `*QueryRepository`로 둔다.
- 읽고 쓰는 일이 섞인 유스케이스(예: 정산 Job)는 쓰기로 보고 `*Command`로 취급한다.
- 서비스 클래스는 읽기와 쓰기로 나누지 않고 `*Service` 하나로 둔다. 코드 모델도 읽기용과 쓰기용으로 분리하지 않는다.

**이유**: 읽기와 쓰기의 책임을 이름에서 구분하되, 클래스 수를 늘리지 않는 가장 가벼운 방식이다. 서비스를 `*CommandService`와 `*QueryService`로 나누는 것은 서비스가 커졌을 때 다시 정한다(향후 개선 사항).

```java
// 좋은 예
record SignupCommand(String loginId, String password, ...) { }          // 쓰기 입력
record ProductSearchQuery(String keyword, ...) { }                       // 읽기 조건 (예시)
class PayoutQueryRepository { ... }                                      // 조회 전용 저장소

// 나쁜 예: 읽기 조건에 Command를 쓰거나, 조회 전용 저장소 이름에 Query가 없다
record ProductSearchCommand(String keyword, ...) { }
class PayoutFindRepository { ... }
```

## 4. 엔티티

### 4.1 공통 부모

**규칙**: 엔티티가 공통으로 상속하는 부모는 생성·수정 시각(`createdAt`, `updatedAt`)만 가진 `BaseTime`이다. ID는 부모에 두지 않고 엔티티마다 정의한다.

**이유**: 생성·수정 시각은 정책상 데이터의 생성과 변경에 대한 기록을 남기기 위해 모든 엔티티에 둔다. 모든 엔티티가 같은 방식이라 공통 부모로 둔다. 반면 ID 전략은 나중에 성능이나 비즈니스 요구에 따라 다르게 가져갈 수 있어서, 공통 부모에 고정하면 엔티티마다 전략을 바꾸기 어렵다.

> 현재 코드는 ID와 시각을 함께 가진 `BaseIdAndTime`이다. `BaseTime`으로의 전환은 별도 작업에서 한다.

```java
// 좋은 예: 시각은 BaseTime, ID는 엔티티에서 정의한다
@Entity
public class Wallet extends BaseTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    ...
}

// 나쁜 예: ID까지 공통 부모에 고정한다 (현재의 BaseIdAndTime)
public class Wallet extends BaseIdAndTime { ... }
```

### 4.2 삭제 방식

**규칙**: 데이터 삭제는 soft delete로 처리한다. 삭제 시각은 `deleted_at` 컬럼에 기록한다.

**이유**: 행을 실제로 지우지 않고 삭제 시각만 남기면 삭제한 데이터를 추적할 수 있다.

```java
// 좋은 예: 삭제 시각을 컬럼에 기록한다 (CartItem)
@Column(name = "deleted_at")
private LocalDateTime deletedAt;

// 나쁜 예: 행을 실제로 지운다
cartItemRepository.delete(cartItem);
```

## 5. DTO·API

### 5.1 URL

**규칙**
- API 경로는 `/api/v1`로 시작한다.
- 리소스 이름은 복수형 명사로 쓴다(`/members`). `media`처럼 복수형이 따로 없는 명사는 그대로 쓴다.
- 생성, 조회, 수정, 삭제는 HTTP 메서드로 표현한다.
- 업무 처리 성격의 행위(회원가입, 로그인, 정산 실행 등)는 리소스 아래에 동사 하위 경로를 허용한다(`/members/signup`).

**이유**: 복수형 명사와 HTTP 메서드의 조합은 가장 널리 쓰이는 REST 관례라 API를 예측하기 쉽다. 로그인이나 정산 실행처럼 리소스의 생성·수정으로 자연스럽게 표현되지 않는 행위까지 명사로 바꾸면 오히려 읽기 어렵다.

```
// 좋은 예
POST /api/v1/members/signup           회원가입 (업무 처리는 동사 하위 경로)
POST /api/v1/media/{purpose}          미디어 업로드
GET  /api/v1/products/{productId}     상품 조회 (예시)

// 나쁜 예
POST /api/v1/payout/run               단수형 (현재 PayoutController)
GET  /api/v1/getProduct               조회를 URL에 쓴다 (예시)
```

### 5.2 멱등성

**규칙**
- 멱등키는 필수가 아니라 선택 사항이다. HTTP 메서드에 멱등성이 없는 메서드(주로 `POST`)를 쓰는데 멱등성이 필요한 API에서만 쓴다.
- 이 경우 클라이언트가 보내는 멱등키를 요청 헤더 `Idempotency-Key`로 받아 처리한다.
- 같은 멱등키로 다시 요청하면 처리를 반복하지 않고 첫 요청의 결과를 돌려준다.

**이유**: 네트워크 재시도나 중복 클릭으로 같은 요청이 두 번 와도 한 번만 처리되어야 한다(예: 주문이 두 번 생성되는 것을 막는다). 멱등키로 요청을 식별하면 요청 본문이 같은 별개의 요청과 구분할 수 있다.

> 멱등키를 처리하는 공통 구현은 아직 없다. 필요한 API가 생길 때 추가한다. 이벤트 처리의 멱등성(예: 정산 후보 생성의 중복 방지)은 이 규칙이 아니라 유니크 제약과 존재 확인으로 처리한다.

```http
# 좋은 예: 멱등키를 헤더로 보낸다 (주문 API는 예시)
POST /api/v1/orders
Idempotency-Key: 8f14e45f-ceea-4e2f-9c3e-5b6d0d7a1c11
```

### 5.3 Swagger 문서화

**규칙**
- 요청·응답 DTO의 모든 필드에 `@Schema(description = ...)`를 쓴다. 예시(`example`)는 형식이 정해져 있거나 값의 모양이 중요한 필드에 쓴다.
- 컨트롤러 Spec(3.3)에는 성공 응답과 해당 API가 실제로 반환하는 주요 오류를 `@ApiResponse`로 적는다. 오류 응답 JSON 예시는 클라이언트가 분기해야 하는 오류(예: 409 중복)에만 쓴다.

**이유**: 모든 필드에 설명이 있어야 Swagger만 보고 API를 쓸 수 있다. 모든 오류에 예시를 강제하면 Spec이 지나치게 길어져 유지하기 어렵다.

```java
// 좋은 예: 필드마다 설명을 쓰고, 형식이 정해진 필드는 예시도 쓴다 (SignupRequest, 일부 생략)
@Schema(description = "로그인 아이디 (영문, 숫자 4~20자)", example = "tester01")
String loginId,

// 좋은 예: Spec에 성공 응답과 주요 오류를 적는다 (MemberControllerSpec 예시, 일부 생략)
@ApiResponse(responseCode = "201", description = "회원가입 성공")
@ApiResponse(responseCode = "409", description = "아이디 또는 이메일 중복")
@ApiResponse(responseCode = "422", description = "요청 값 검증 실패")

// 나쁜 예: 필드에 설명이 없다 (MediaInfo)
public record MediaInfo(long mediaId, String url) { }

// 나쁜 예: 성공 응답만 적고 반환할 수 있는 오류를 적지 않는다 (MediaApiSpec)
@ApiResponse(responseCode = "201", description = "업로드 성공")
```

### 5.4 값 정규화·변환

**규칙**
- 형식 정규화(공백 제거, 대소문자 통일 등)는 요청 DTO의 컴팩트 생성자에서 한다. 검증은 정규화된 값을 기준으로 수행된다. 공백도 값의 일부일 수 있는 필드(비밀번호 등)는 정규화하지 않는다.
- 요청 DTO → `*Command` 변환은 요청 DTO의 `toCommand()`에서 한다.
- 비즈니스 로직에 속하는 값 가공(비밀번호 암호화, 시각 기록, 상태 결정 등)은 서비스에서 한다.

**이유**: 형식 정규화는 요청 형식 검증과 짝을 이루므로 같은 곳(DTO)에 둔다. 서비스에서 정규화하면 정규화되기 전의 값이 먼저 검증에 걸린다(예: 대문자로 입력한 아이디가 소문자 패턴에서 거절된다). 값을 가공해서 저장하는 일은 비즈니스 결정이라 서비스가 맡는다.

```java
// 좋은 예: 요청 DTO가 정규화하고 Command로 변환한다 (SignupRequest, 일부 생략)
public SignupRequest {
    loginId = toLowerCaseTrimmed(loginId);
    name = trim(name);
}

public SignupCommand toCommand() {
    return new SignupCommand(loginId, password, email, name, nickname);
}

// 좋은 예: 비즈니스 값 가공은 서비스에서 한다 (MemberSignupService)
String encodedPassword = passwordEncoder.encode(command.password());

// 나쁜 예: 서비스에서 정규화한다 (대문자로 입력한 값이 정규화되기 전에 형식 검증에서 거절된다)
String loginId = command.loginId().toLowerCase();
```

## 6. 예외 처리

### 6.1 예외 종류

**규칙**
- 예외 종류는 호출 경로(HTTP, 이벤트, 배치)가 아니라 **값의 출처**로 정한다. 같은 값의 검사는 어느 경로로 들어와도 같은 예외를 던진다.
- 사용자 입력이 닿을 수 있는 값의 오류(입력값 오류, 도메인 규칙 위반, 상태 충돌)는 `RestApiException`에 모듈의 `ErrorCode`를 담아 던진다.
- 다른 모듈이나 시스템이 계산해서 넘긴 값의 오류는 호출한 코드의 버그로 보고 표준 예외(`IllegalArgumentException`, `IllegalStateException`)를 던진다. 이벤트 리스너나 배치가 받은 값도 같다.
- 상태 충돌(현재 상태에서 허용되지 않는 전이)은 호출 경로와 관계없이 `RestApiException`이다. 시스템 경로(택배사 알림, 자동 구매확정 배치 등)에서 호출해도 같다.

**이유**: 사용자가 고칠 수 있는 오류는 에러 코드로 유형을 구분해야 클라이언트가 분기할 수 있다. 시스템이 만든 값이 잘못된 것은 사용자가 고칠 수 없는 버그다. 처리되지 않은 예외로 500이 되어야 모니터링에서 실제 장애로 잡히고, 4xx 에러 코드를 붙이면 사용자 입력 오류에 섞여 놓친다. 호출 경로로 정하면 같은 엔티티의 같은 검사가 어디서 호출하느냐에 따라 달라져야 해서 기준이 흔들린다. 상태 전이 메서드는 사용자와 시스템이 함께 호출하고, 충돌은 값이 아니라 현재 상태 때문에 생기므로 한 가지로 정한다. 시스템 경로에서 같은 알림이 중복으로 와서 충돌이 나면, 호출하는 쪽이 멱등 처리로 거른다.

> 정산 모듈은 이벤트·배치 경로에서 받은 값의 오류에 `PayoutErrorCode`를 쓰고 있다(`PayoutCandidateService` 등). 정산 담당과 협의해 정리한다(#117).

```java
// 좋은 예: 사용자 입력 오류는 RestApiException + ErrorCode
if (quantity < 1) {
    throw new RestApiException(OrderErrorCode.INVALID_QUANTITY);
}

// 좋은 예: 다른 모듈이 넘긴 값의 오류는 표준 예외 (이벤트 리스너가 받은 값도 같다)
if (event.orderItemId() == null) {
    throw new IllegalArgumentException("orderItemId는 필수입니다.");
}

// 좋은 예: 시스템이 계산한 값의 오류도 표준 예외
if (baseShippingFee < 0) {
    throw new IllegalArgumentException("배송비 금액은 0 이상이어야 합니다.");
}

// 나쁜 예: 시스템이 계산한 값에 4xx 에러 코드를 쓴다 (버그가 사용자 입력 오류로 기록된다)
if (baseShippingFee < 0) {
    throw new RestApiException(DeliveryErrorCode.INVALID_SHIPPING_FEE);
}

// 나쁜 예: 사용자 입력 오류에 표준 예외를 쓴다 (클라이언트가 유형을 구분할 수 없고 500이 된다)
if (quantity < 1) {
    throw new IllegalArgumentException("수량은 1개 이상이어야 합니다.");
}
```

### 6.2 에러 코드

**규칙**
- 모든 모듈은 자기 `*ErrorCode` enum(`ErrorCode` 구현)을 만들고, 도메인 오류는 그 enum으로 던진다.
- 에러 코드 enum은 모듈의 `exception` 패키지에 둔다(예: `com.ok.order.exception.OrderErrorCode`).
- `CommonErrorCode`는 입력 검증 실패, 잘못된 JSON, 용량 초과 같은 HTTP 수준의 공통 오류에만 쓴다. 모듈의 도메인 오류에 `CommonErrorCode`와 직접 쓴 메시지를 조합하지 않는다.

**이유**: 오류 유형을 에러 코드로 구분해야 클라이언트가 분기할 수 있다. `CommonErrorCode.BAD_REQUEST`처럼 하나의 코드에 메시지만 바꿔 쓰면 빈 파일, 미지원 형식 같은 서로 다른 오류가 같은 유형이 된다.

```java
// 좋은 예: 모듈의 에러 코드로 유형을 구분한다 (MediaErrorCode는 예시)
throw new RestApiException(MediaErrorCode.EMPTY_FILE);
throw new RestApiException(MediaErrorCode.UNSUPPORTED_MEDIA_TYPE);

// 나쁜 예: 공통 코드에 메시지만 바꿔 써서 유형이 구분되지 않는다
throw new RestApiException(CommonErrorCode.BAD_REQUEST, "빈 파일은 업로드할 수 없습니다.");
throw new RestApiException(CommonErrorCode.BAD_REQUEST, "해당 파일은 지원하지 않는 형식입니다.");
```

### 6.3 에러 응답의 `type` 주소(slug)

**규칙**
- 모듈 에러 코드의 `type` slug는 `<모듈>/<에러 코드 이름>` 형식으로 한다. 에러 코드 이름은 enum 이름을 소문자와 하이픈으로 바꾼 값이다(`MEMBER_NOT_FOUND` → `member-not-found`).
- `CommonErrorCode`는 접두사 없이 쓴다(`validation-failed`, `bad-request` 등).
- 배포한 뒤에는 `type`을 바꾸지 않는다.

**이유**: 모듈마다 같은 이름의 에러 코드가 생겨도 `type`이 겹치지 않고, 클라이언트가 `type`만 보고 어느 모듈의 오류인지 알 수 있다. `type`은 클라이언트가 분기에 쓰는 값이라 배포한 뒤에는 바꿀 수 없으므로, 처음부터 정한다.

```
// 좋은 예: 모듈 접두사 + 이름 (앞의 {base}는 PROBLEM_BASE_URI)
{base}/member/duplicate-email     MemberErrorCode.DUPLICATE_EMAIL
{base}/order/member-not-found     OrderErrorCode.MEMBER_NOT_FOUND
{base}/validation-failed          CommonErrorCode.VALIDATION_FAILED   (공통 오류는 접두사 없음)

// 나쁜 예: 모듈 접두사가 없어 다른 모듈의 같은 이름과 겹칠 수 있다
{base}/member-not-found
```

### 6.4 에러 코드의 `title`

**규칙**: `title`은 영어로, 첫 글자만 대문자인 짧은 문장으로 쓴다(`Member not found`). 값을 끼워 넣지 않고 유형별로 고정한다. 한국어 설명은 `detail`(에러 코드의 `message`)에 쓴다.

**이유**: `title`은 오류 유형의 짧은 이름이라 유형마다 고정되어야 하고, 상황에 따라 달라지는 설명은 `detail`이 맡는다. 표기를 맞추면 응답이 일관된다.

```java
// 좋은 예
MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "Member not found", "회원 정보를 찾을 수 없습니다."),

// 나쁜 예: 소문자로 시작한다
CONTENT_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "content too large", "요청 페이로드가 서버 허용 제한을 초과하였습니다."),

// 나쁜 예: 한국어이거나 값이 끼어 있다
MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원 123을 찾을 수 없습니다", "회원 정보를 찾을 수 없습니다."),
```

### 6.5 에러 코드 작성

**규칙**
- 에러 코드 enum은 `ErrorCode`를 구현하고, 항목마다 HTTP 상태, `title`, 기본 `detail`(`message`)을 둔다. 예외는 `RestApiException`으로 던진다.
- `detail`에는 응답으로 내보내도 되는 값만 넣는다(요청에서 받은 값, 식별자 정도). 내부 예외 메시지나 SQL 오류를 넣지 않는다.

**이유**: `detail`은 클라이언트에 그대로 전달되므로, 내부 구현 정보가 새어 나가지 않게 한다.

```java
// 좋은 예
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found", "주문을 찾을 수 없습니다."),
    ORDER_ALREADY_PAID(HttpStatus.CONFLICT, "Order already paid", "이미 결제된 주문입니다.");

    private final HttpStatus httpStatus;
    private final String title;     // 유형별 고정 제목
    private final String message;   // 기본 detail

    @Override
    public HttpStatus status() { return httpStatus; }
}

throw new RestApiException(OrderErrorCode.ORDER_NOT_FOUND);                        // 기본 detail
throw new RestApiException(OrderErrorCode.ORDER_NOT_FOUND, "주문 123을 찾을 수 없습니다."); // detail 지정

// 나쁜 예: 내부 예외 메시지를 detail에 넣는다
throw new RestApiException(OrderErrorCode.ORDER_NOT_FOUND, e.getMessage());
```

### 6.6 검증 위치

**규칙**
- 요청 형식 검증(필수값, 길이, 패턴, 범위)은 요청 DTO의 Bean Validation으로 하고, 컨트롤러에서 `@Valid`로 검증한다.
- 비즈니스 규칙 검증(중복 여부, 상태, 정책)은 서비스나 도메인(엔티티, 정책)에서 한다.
- 엔티티·값 객체의 불변식(생성할 때 반드시 지켜야 하는 조건)은 생성자나 정적 팩토리에서 검증한다. 위반한 값이 사용자 입력에서 올 수 있으면 `RestApiException`, 다른 모듈이나 시스템이 넘긴 값이면 표준 예외를 던진다(6.1).

**이유**: 요청 형식 오류는 서비스에 도착하기 전에 422로 거절하고 필드별 오류를 돌려줄 수 있다. 비즈니스 규칙은 요청 형식과 상관없이 지켜야 하므로 서비스나 도메인이 맡는다. 불변식을 객체가 스스로 검증하면 어떤 경로로 만들어도 유효한 객체만 존재한다.

```java
// 좋은 예: 요청 형식은 DTO의 Bean Validation
public record SignupRequest(
        @NotBlank(message = "아이디는 필수입니다.")
        @Pattern(regexp = "^[a-z0-9]{4,20}$", message = "아이디는 영문과 숫자로 4~20자여야 합니다.")
        String loginId, ...) { }

// 좋은 예: 비즈니스 규칙(중복 여부)은 서비스(검증기)에서
if (memberRepository.existsByLoginId(loginId)) {
    throw new RestApiException(MemberErrorCode.DUPLICATE_LOGIN_ID);
}

// 좋은 예: 불변식은 엔티티 생성자에서
private Wallet(Long memberId, WalletType type) {
    if (memberId == null) {
        throw new IllegalArgumentException("memberId는 필수입니다.");
    }
    ...
}

// 나쁜 예: 불변식을 호출하는 쪽에서만 확인한다 (다른 곳에서 만들면 잘못된 객체가 생긴다)
if (memberId == null) { throw ...; }
Wallet.create(memberId, type);      // 생성자에는 검증이 없다
```

## 7. 서비스·트랜잭션

### 7.1 트랜잭션 선언

**규칙**
- 서비스 클래스에 `@Transactional(readOnly = true)`를 선언하고, 데이터를 변경하는 메서드에만 `@Transactional`을 붙인다.
- 같은 클래스 안에서 `@Transactional` 메서드를 호출하지 않는다.

**이유**: 기본값이 읽기 전용이면 조회 메서드에 따로 선언할 필요가 없고 실수로 쓰기가 섞이기 어렵다. 변경 메서드에만 `@Transactional`이 붙어서 데이터가 바뀌는 지점이 눈에 띈다. 같은 클래스 안의 호출은 프록시를 거치지 않아 `@Transactional`이 무시되고 클래스 기본값(읽기 전용)으로 실행된다.

```java
// 좋은 예
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletService {

    @Transactional
    public void createBuyerWallet(Long memberId) { ... }      // 변경 메서드

    public Wallet getWallet(Long memberId) { ... }            // 조회는 기본값을 따른다 (예시)
}

// 나쁜 예: 변경 메서드에 @Transactional을 빠뜨린다 (읽기 전용으로 실행되어 변경이 반영되지 않을 수 있다)
public void createBuyerWallet(Long memberId) { ... }
```

### 7.2 외부 호출과 Facade

**규칙**
- 외부 시스템 호출(S3, 택배사 API 등)은 서비스가 아니라 `*Facade`에서 한다. Facade에는 `@Transactional`을 붙이지 않는다.
- DB 작업은 Facade가 서비스를 호출해서 한다. Facade는 `service` 패키지에 둔다.

**이유**: 외부 호출이 느릴 때 트랜잭션과 DB 커넥션을 오래 잡지 않도록, 외부 호출은 트랜잭션 밖에서 하고 DB 저장만 트랜잭션에 넣는다. Facade가 서비스를 다른 빈으로 호출하므로 `@Transactional`도 정상 동작한다. 단, Facade에서 서비스를 여러 번 호출하면 호출마다 별도 트랜잭션이므로, 원자적으로 처리해야 하는 DB 작업은 서비스의 한 메서드에 둔다.

**주의**: 외부 시스템과 DB는 같은 트랜잭션으로 묶을 수 없다. 한쪽이 성공한 뒤 다른 쪽이 실패하면 데이터가 어긋나므로, 성공한 쪽을 되돌리는 **보상 처리(보상 트랜잭션)** 를 Facade에서 한다. 예를 들어 S3 업로드는 성공했는데 메타데이터 저장이 실패하면 업로드한 파일을 삭제한다. 보상 처리도 실패할 수 있으므로, 실패하면 로그를 남겨 나중에 정리할 수 있게 한다.

```java
// 좋은 예: Facade가 외부 호출(트랜잭션 밖), 서비스가 DB 저장(트랜잭션), 실패하면 보상 (클래스 이름은 예시)
@Component
@RequiredArgsConstructor
public class MediaUploadFacade {

    private final FileStorage fileStorage;
    private final MediaService mediaService;

    public MediaUploadResult upload(MediaPurpose purpose, MultipartFile file) {
        String objectKey = fileStorage.store(...);                // 외부 호출, 트랜잭션 없음
        try {
            return mediaService.save(purpose, objectKey, ...);    // DB 저장, 트랜잭션
        } catch (RuntimeException e) {
            fileStorage.delete(objectKey);                        // 보상 처리: 업로드한 파일을 되돌린다
            throw e;
        }
    }
}

// 나쁜 예: 트랜잭션이 걸린 서비스 안에서 외부 호출을 한다
@Transactional
public MediaInfo upload(MediaPurpose purpose, MultipartFile file) {
    s3Client.putObject(...);
    return mediaRepository.save(...);
}
```

### 7.3 현재 시각

**규칙**
- 현재 시각은 `Clock`을 주입받아 `LocalDateTime.now(clock)`처럼 얻는다. `LocalDateTime.now()`를 직접 호출하지 않는다.
- 테스트에서는 고정된 시각의 `Clock`을 사용한다.
- `Clock` 빈은 `com.ok.config.ClockConfig`에 있다. JVM 기본 시간대(Asia/Seoul로 고정)를 따른다.
- 모듈 테스트(`@ApplicationModuleTest`)에서는 `testsupport/TestClockConfiguration`을 `@Import`한다.

**이유**: 시각에 의존하는 로직을 현재 시각과 상관없이 같은 결과로 테스트할 수 있다. 또한 `LocalDateTime.now()`는 JVM의 기본 타임존을 사용하므로, 앱의 타임존 설정(`APP_TIME_ZONE`)과 어긋날 수 있다. `Clock` 빈을 한 곳(`ClockConfig`)에 두고, JVM 시간대와 `APP_TIME_ZONE`을 모두 Asia/Seoul로 고정해 이를 막는다.

```java
// 좋은 예
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberSignupService {

    private final Clock clock;

    @Transactional
    public Long signup(SignupCommand command) {
        LocalDateTime agreedAt = LocalDateTime.now(clock);
        ...
    }
}

// 좋은 예: 테스트에서는 고정된 시각의 Clock을 넣는다
Clock clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);

// 나쁜 예: 현재 시각에 따라 결과가 달라지고 앱의 타임존 설정과 어긋날 수 있다
LocalDateTime agreedAt = LocalDateTime.now();
```

### 7.4 로그

**규칙**
- 로그 메시지는 한국어로 쓰고, 식별자를 `key={}` 형태로 넣는다(`orderItemId={}`).
- 레벨은 아래 기준으로 고른다.
- 비밀번호, 토큰, 개인정보는 로그에 남기지 않는다.
- 예외를 던질 때 로그를 따로 남기지 않는다. 처리되지 않은 예외는 `ApiControllerAdvice`가 한 번만 남긴다.

| 레벨 | 쓰는 경우 | 예 |
|---|---|---|
| `ERROR` | 처리되지 못한 예외(서버 오류) | `ApiControllerAdvice`의 처리되지 않은 예외 |
| `WARN` | 계속 진행하지만 확인이 필요한 이상 | 보상 처리 실패 |
| `INFO` | 업무상 의미 있는 사건 | 배치 종료, 중복 이벤트 무시 |
| `DEBUG` | 개발용 상세 | 중간 계산 값 |

**이유**: 문구와 레벨이 일정해야 로그를 검색하고 읽기 쉽다. 예외를 던지면서 로그도 남기면 같은 예외가 여러 번 기록되고, 개인정보가 로그에 남으면 유출 위험이 있다.

```java
// 좋은 예
log.info("이미 처리된 구매확정 이벤트, 무시. orderItemId={}", event.orderItemId());
log.info("정산 Job 종료 executionId={}, status={}", execution.getId(), execution.getStatus());

// 나쁜 예: 식별자를 문자열로 이어 붙이고 비밀번호를 남긴다
log.info("가입 처리 loginId=" + loginId + ", password=" + password);

// 나쁜 예: 예외를 로그로 남기고 다시 던진다 (ApiControllerAdvice에서도 남겨 중복된다)
catch (RuntimeException e) {
    log.error("저장 실패", e);
    throw e;
}
```

### 7.5 서비스를 나누는 기준

**규칙**
- 도메인당 `*Service` 하나로 시작한다.
- 한 서비스의 책임이 서로 다른 이유로 변경되거나 의존하는 빈이 많아지면, 유스케이스 단위의 서비스로 나눈다.

**이유**: 서비스를 처음부터 잘게 나누면 클래스만 늘어난다. 나눌 이유는 코드가 커지면서 드러나므로, 그때 나눈다.

```java
// 좋은 예: 도메인 하나에 서비스 하나로 시작한다
class WalletService { ... }

// 좋은 예: 책임이 갈라져 유스케이스 단위로 나눈 경우
class MemberSignupService { ... }

// 나쁜 예: 처음부터 메서드 하나짜리 서비스를 유스케이스마다 만든다 (예시)
class WalletCreateService { ... }
class WalletChargeService { ... }
class WalletQueryService { ... }
```

## 8. DB 마이그레이션 (Flyway)

### 8.1 파일 작성

**규칙**
- 스키마 변경은 항상 `src/main/resources/db/migration`에 SQL 파일로 추가한다.
- 파일명은 `V{yyyyMMddHHmm}__{도메인}__{설명}.sql`로 한다. 구분자는 밑줄 두 개, 설명 안의 단어 구분은 밑줄 한 개다.
  - 버전은 파일을 만드는 시점의 시각(12자리)이다.
  - 도메인은 모듈 이름(`member`, `order`, `common` 등)이다.
  - 설명은 `create_*_table`, `add_*_to_*`, `add_index_*`처럼 동작으로 시작한다.
- 엔티티를 추가하거나 수정하면 대응하는 마이그레이션을 반드시 함께 추가한다.

**이유**: 시각을 버전으로 쓰면 순번(`V2`, `V3`)과 달리 브랜치끼리 버전이 겹치지 않는다. `ddl-auto`가 `validate`라서 마이그레이션 없이 엔티티만 바꾸면 앱이 기동하지 않고, 테스트에서도 같은 검증이 돈다.

```
// 좋은 예
V202610091242__wallet__create_wallet_table.sql

// 나쁜 예: 도메인과 설명 사이 구분자가 밑줄 하나이고, 설명이 create_*_table 형태가 아니다
V202610091620__member_create_member.sql
```

### 8.2 적용된 파일과 병합 순서

**규칙**
- 이미 적용된 마이그레이션은 수정하지 않는다. 변경이 필요하면 새 버전 파일을 추가한다. 로컬에서만 쓴 파일이면 `data/mysql`을 지우고 다시 적용해도 된다.
- 개발 환경(dev 프로파일)은 `out-of-order`를 허용해서 늦은 버전이 먼저 병합되어도 적용한다. 운영은 순서대로 적용되도록 허용하지 않는다.
- 운영은 `main`의 코드로 실행되므로, 새 마이그레이션의 버전은 `main`에 이미 있는 가장 최신 버전보다 뒤여야 한다. 병합 전에 `main`을 합쳐서 확인하고, 뒤처졌으면 아직 병합되지 않은 내 파일의 버전을 현재 시각으로 바꾼다.
- 이벤트 발행 기록 테이블 마이그레이션(`...common__create_event_publication_table.sql`)은 Spring Modulith가 쓰므로 지우지 않는다.

**이유**: 이미 적용된 파일을 수정하면 체크섬이 달라져 앱이 기동하지 않는다. 개발 환경은 여러 브랜치의 병합 시점이 달라질 수 있어서 순서를 허용한다. 운영은 순서대로 적용되므로, 운영 DB에 이미 적용된 버전보다 오래된 파일이 나중에 올라가면 오류가 난다.

```yaml
# application-dev.yml: 개발 환경에서만 순서 무시를 허용한다
spring:
  flyway:
    out-of-order: true
```

### 8.3 테이블 옵션

**규칙**: 모든 `CREATE TABLE`에 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4`를 명시한다. collation은 DB 기본값을 따르고 테이블에서 따로 지정하지 않는다. 특정 컬럼에 다른 비교 규칙이 꼭 필요할 때만 그 컬럼에 지정한다.

**이유**: 엔진과 문자셋을 명시하면 DB 기본 설정이 달라도 같은 결과가 나온다. collation을 테이블마다 지정하면 비교 규칙이 어긋나 대소문자 구분 같은 동작이 테이블마다 달라질 수 있다.

```sql
-- 좋은 예 (cart 테이블)
CREATE TABLE cart (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ...
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 나쁜 예: 옵션이 없다 (media 테이블)
CREATE TABLE media ( ... );

-- 나쁜 예: collation을 테이블에 따로 지정한다 (wallet 테이블)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
```

### 8.4 제약·인덱스 이름

**규칙**: 모든 제약과 인덱스에 이름을 지정한다. 형식은 `{접두사}_{테이블}_{컬럼들}`이고, 접두사는 유니크 `uk`, 체크 `ck`, 외래키 `fk`, 인덱스 `idx`다. 컬럼 이름은 줄이지 않고 그대로 쓴다.

**이유**: 이름이 있어야 코드에서 제약 위반을 구별할 수 있다. `MemberDuplicateValidator`는 DB 예외 메시지에 들어 있는 제약 이름(`uk_member_login_id`)으로 어떤 중복인지 판별해 에러 코드로 바꾼다. 형식이 같으면 이름만 보고 대상 테이블과 컬럼을 알 수 있다.

```sql
-- 좋은 예
CONSTRAINT uk_member_login_id UNIQUE (login_id),
CONSTRAINT fk_payout_item_payout_id FOREIGN KEY (payout_id) REFERENCES payout (id)
CREATE INDEX idx_payout_candidate_items_created_at ON payout_candidate_items (created_at);

-- 나쁜 예: 이름이 없다 (media 테이블)
UNIQUE (object_key)

-- 나쁜 예: 컬럼 이름을 줄였다 (wallet 테이블, member_id를 member로 썼다)
CONSTRAINT uk_wallet_member_type UNIQUE (member_id, type)
```

### 8.5 테이블 이름

**규칙**: 테이블 이름은 단수형 snake_case로 쓰고, 엔티티 이름과 대응하게 짓는다(`CartItem` → `cart_item`).

**이유**: 테이블 이름이 엔티티 이름과 1:1로 대응하면 찾기 쉽고, 단수형으로 통일하면 모듈마다 달라지지 않는다.

```sql
-- 좋은 예
CREATE TABLE cart_item ( ... )
CREATE TABLE payout_item ( ... )

-- 나쁜 예: 복수형을 쓴다
CREATE TABLE payout_candidate_items ( ... )
```

### 8.6 외래키

**규칙**
- 같은 모듈 안의 엔티티 연관(`@ManyToOne` 등)에는 외래키를 둔다.
- 다른 모듈의 데이터를 가리키는 ID 컬럼에는 외래키를 두지 않는다.
- 다른 모듈의 데이터를 복제본(예: order 모듈의 `market_member`)으로 가지고 있는 경우, 복제본을 가리키는 컬럼은 외래키가 없을 수 있다. 복제본이 원본 모듈의 이벤트로 만들어지고 동기화된다면 참조하는 시점에 아직 행이 없을 수 있기 때문이다.

**이유**: 같은 모듈 안에서는 DB가 데이터 정합성을 지켜 주고 조회용 인덱스도 함께 생긴다. 다른 모듈의 데이터까지 외래키로 묶으면 모듈이 DB 수준으로 결합되어 독립성을 해친다.

```sql
-- 좋은 예: 같은 모듈 안의 연관은 외래키 (payout_item 테이블)
CONSTRAINT fk_payout_item_payout_id FOREIGN KEY (payout_id) REFERENCES payout (id)

-- 좋은 예: 다른 모듈의 데이터를 가리키는 ID는 외래키가 없다 (wallet 테이블)
member_id BIGINT NOT NULL,

-- 나쁜 예: 같은 모듈 안의 연관인데 외래키도 인덱스도 없다 (cart_item 테이블)
cart_id BIGINT NOT NULL,
```

### 8.7 컬럼 주석(`COMMENT`)

**규칙**: 이름만으로 의미가 분명하지 않은 컬럼에는 `COMMENT`를 쓴다(다른 모듈의 ID, 단위가 있는 값, 상태·플래그, 값에 의미가 있는 컬럼). `id`, `created_at`처럼 자명한 컬럼에는 쓰지 않는다.

**이유**: 이름만으로 알기 어려운 단위나 출처를 DB에서도 확인할 수 있게 하고, 자명한 설명으로 SQL이 길어지지 않게 한다.

```sql
-- 좋은 예
product_id  BIGINT  NOT NULL COMMENT '상품 모듈 ID',
file_size   BIGINT  NOT NULL COMMENT '파일 크기 (bytes)',
purchasable BOOLEAN NOT NULL DEFAULT TRUE COMMENT '구매 가능 여부',

-- 나쁜 예: 자명한 컬럼에 설명을 단다
id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '아이디',
created_at DATETIME(6) NOT NULL COMMENT '생성 일시',

-- 나쁜 예: 단위나 의미를 알기 어려운데 설명이 없다 (payout 테이블)
amount BIGINT NOT NULL,
```

### 8.8 SQL 포맷

**규칙**: SQL 키워드는 대문자, 이름은 소문자 snake_case로 쓴다. `CREATE TABLE 이름 (`처럼 여는 괄호는 같은 줄에 두고, 컬럼은 4칸 들여쓴다. 괄호 위치에 맞춰 깊게 들여쓰지 않는다. 이 규칙은 앞으로 쓰는 파일에 적용한다(이미 적용된 파일은 수정하지 않는다, 8.2).

**이유**: 들여쓰기가 깊으면 줄이 길어지고 테이블 이름이 바뀔 때 전체를 다시 맞춰야 한다. 4칸으로 고정하면 변경(diff)도 작다.

```sql
-- 좋은 예 (예시)
CREATE TABLE wallet (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 나쁜 예: 괄호 위치에 맞춰 깊게 들여쓴다 (cart 테이블)
CREATE TABLE cart (
                      id         BIGINT      NOT NULL AUTO_INCREMENT,
                      member_id  BIGINT      NOT NULL COMMENT '회원 ID',

-- 나쁜 예: 여는 괄호를 다음 줄에 둔다 (member 테이블)
CREATE TABLE member
(
    id BIGINT NOT NULL AUTO_INCREMENT,
```

## 9. 테스트

### 9.0 작성 순서

**규칙**
- 기능을 구현한 뒤 테스트 코드를 작성하고, 테스트를 실행해 통과하는지 확인한다. 테스트가 실패하면 구현이나 테스트를 고쳐서 다시 확인한다.
- 의미 없는 테스트 코드는 작성하지 않는다(예: 검증할 동작이 없는 코드).

**이유**: TDD의 정석은 테스트를 먼저 작성하고 실패를 확인한 뒤 구현하는 것이지만, 편의성을 위해 구현 후 테스트를 작성하는 순서를 따른다.

### 9.1 테스트 메서드 이름

**규칙**: 테스트 메서드 이름은 한글로 동작을 설명한다. `@DisplayName`은 쓰지 않는다.

**이유**: 한글 메서드 이름이 이미 동작을 설명하므로 `@DisplayName`은 같은 내용을 한 번 더 적는 중복이 된다. 이름 하나만 보면 되도록 한다.

```java
// 좋은 예
@Test
void 지갑이_이미_있으면_저장하지_않는다() { ... }

// 나쁜 예: 이름과 설명이 중복되고, 영어 이름은 동작을 바로 알기 어렵다
@Test
@DisplayName("지갑이 이미 있으면 저장하지 않는다")
void createBuyerWallet_alreadyExists() { ... }
```

### 9.2 본문 구역

**규칙**: 테스트 본문은 `// given`, `// when`, `// then` 주석으로 구역을 나눈다. 주석은 소문자로 쓴다. 예외를 검증하는 테스트는 `when`과 `then`을 나누지 않고 `// when & then` 하나로 합친다.

**이유**: 준비, 실행, 검증이 한눈에 구분되어야 읽기 쉽다. 예외 검증은 실행과 검증이 `assertThatThrownBy` 한 문장에 함께 들어가므로 나누면 오히려 장황해진다.

```java
// 좋은 예: 정상 케이스는 세 구역
@Test
void 지갑을_생성하면_잔액은_0이다() {
    // given
    Long memberId = 1L;

    // when
    Wallet sut = Wallet.create(memberId, WalletType.BUYER);

    // then
    assertThat(sut.getBalance()).isZero();
}

// 좋은 예: 예외 케이스는 when & then
@Test
void memberId가_없으면_지갑을_생성할_수_없다() {
    // given
    Long memberId = null;

    // when & then
    assertThatThrownBy(() -> Wallet.create(memberId, WalletType.BUYER))
            .isInstanceOf(IllegalArgumentException.class);
}

// 나쁜 예: 대문자 주석(// Given), Arrange / Act / Assert 표기, 예외를 when과 then으로 분리
```

### 9.3 검증 대상 변수명 `sut`

**규칙**: 테스트 안에서 검증 대상이 되는 객체의 변수명만 `sut`로 쓴다. 다른 변수는 평소처럼 이름을 짓는다. 검증 대상이 되는 변수가 없는 테스트(예외 검증 등)는 억지로 만들지 않는다.

**이유**: 준비물과 검증 대상이 섞여 있을 때 무엇을 검증하는지 바로 보이게 한다.

```java
// 좋은 예
Wallet sut = Wallet.create(memberId, WalletType.BUYER);

// 나쁜 예: 어느 변수가 검증 대상인지 이름으로 알 수 없다
Wallet result = Wallet.create(memberId, WalletType.BUYER);
```

### 9.4 테스트 종류와 사용 기준

**규칙**: 레이어마다 맡는 검증을 나눈다. 정상 흐름은 통합 테스트에서 한 번 검증하고, 서비스 단위 테스트는 분기와 실패, 협력 동작 중심으로 쓴다. 프레젠테이션 레이어(Controller)는 통합 테스트로만 검증하고, 저장소 테스트(`@DataJpaTest`)와 컨트롤러 슬라이스(`@WebMvcTest`)는 쓰지 않는다. 통합 테스트는 `AbstractIntegrationTest`를 상속한다.

| 종류 | 검증하는 것 | 방식 |
|---|---|---|
| 도메인 단위 테스트 | 핵심 비즈니스 로직. 엔티티, 값 객체, 정책 클래스, 알고리즘 등 Spring 없이 돌릴 수 있는 순수 로직 전부 | 순수 JUnit + AssertJ (Spring 없이) |
| 서비스 단위 테스트 | 유스케이스의 분기, 예외, 협력 동작(이벤트 발행, 외부 경계 호출, 실패 경로) | Mockito |
| 통합 테스트 | Controller → Service → Repository 전체 흐름, 실제 DB가 필요한 것(영속성, 쿼리, 제약, 트랜잭션) | `AbstractIntegrationTest` 상속 |
| 모듈 테스트 | 한 모듈 안의 이벤트 연동. 이벤트 발행·수신 같은 모듈 외부 계약이 있는 모듈마다 모듈 테스트 클래스를 둔다 | `@ApplicationModuleTest` |
| 구조 테스트 | 모듈 간 의존 규칙(Modulith), 모듈 안의 레이어 의존 방향과 클래스 이름·위치(ArchUnit) | `ModulithStructureTest`, `ArchitectureTest` |

**이유**: 통합 테스트가 레이어를 통과하며 흐름과 DB 동작을 검증하므로, 저장소와 컨트롤러를 따로 테스트하면 같은 것을 다시 검증하게 된다. 컨트롤러는 단위 테스트와 통합 테스트가 검증하는 내용이 크게 다르지 않다. 반면 서비스의 분기와 실패 경로는 통합 시나리오로 모두 덮으면 너무 많아지므로 단위 테스트가 맡는다. 핵심 비즈니스 로직은 가장 중요하므로 Spring 없이 빠르고 촘촘하게 먼저 검증한다. 또한 스프링 컨텍스트는 설정이 조금이라도 다르면 새로 뜨고(8~10초), `AbstractIntegrationTest`를 상속한 테스트는 컨텍스트 하나를 공유하므로, 컨텍스트가 이미 떠 있으면 테스트 11개가 약 2초에 끝난다.

```java
// 좋은 예
class OrderServiceIntegrationTest extends AbstractIntegrationTest { ... }

// 나쁜 예: 직접 설정하면 컨텍스트가 하나 더 떠서 테스트가 느려진다
@SpringBootTest(properties = "...")
@Import(TestcontainersConfiguration.class)
class OrderServiceIntegrationTest { ... }
```

**구조 테스트 (ArchUnit)**: `ArchitectureTest`는 이 문서의 규칙 중 코드로 검사할 수 있는 것을 검사한다. 규칙마다 `because`에 이유와 근거 절을 적어, 실패 메시지만 보고 원인을 알 수 있게 한다.

| 검사 | 근거 |
|---|---|
| Controller → Service → Repository 방향으로만 의존하고, `domain`은 Service·Repository만 쓴다 | 2.1, 2.3 |
| 패키지 사이에 순환 의존이 없다 (모듈 안 패키지까지) | 2.2 확장 |
| 이벤트 리스너(`event`)는 `controller`, `repository`, `domain`을 쓰지 않는다 | 2.1 |
| `ApplicationEventPublisher`와 이벤트 클래스(`common.event`)는 `event` 패키지만 쓴다 | 2.1 |
| `service/publisher`에는 인터페이스만 둔다 | 2.1 |
| `@RestController`는 `*Controller`, `@Service`는 `*Service`이고 각 레이어 패키지에 있다 | 3.2 |
| 저장소는 `repository`, 엔티티는 `domain`, `ErrorCode` 구현체는 `exception` 패키지에 있다 | 2.1, 3.2 |
| Swagger 문서 인터페이스는 `*Spec`, package-private이고 `spec` 패키지에 두지 않는다 | 3.3 |
| 컨트롤러 클래스·메서드에 `@Transactional`을 붙이지 않는다 | 7.1 |
| `System.out`, `System.err`를 쓰지 않는다 | 일반 |

규칙을 넣을 때 이미 있던 위반은 freeze로 `src/test/resources/archunit_store/`에 기준선으로 저장한다. 기준선에 있는 위반은 통과하고, 새로 생긴 위반만 실패한다. 위반을 고치면 기준선에서 자동으로 빠지므로, 바뀐 `archunit_store` 파일도 같이 커밋한다.

freeze 규칙을 추가하거나 문구(`because` 포함)를 바꾸면 기준선을 다시 만든다. 기준선은 규칙 설명 문장으로 찾으므로 문구만 바뀌어도 다른 규칙으로 본다.

1. `archunit.properties`의 `freeze.store.default.allowStoreCreation`을 `true`로 바꾼다. 이미 기준선이 있는 규칙을 다시 만들려면 `freeze.refreeze=true`도 넣는다.
2. `./gradlew test --tests com.ok.ArchitectureTest`를 실행한다.
3. 설정을 되돌리고, 기록된 위반이 예상과 같은지 확인한 뒤 커밋한다.

Gradle의 `-D` 옵션은 테스트 JVM에 전달되지 않으므로 설정 파일을 직접 고친다.

### 9.5 파라미터화 테스트

**규칙**
- 입력값은 `@MethodSource`로 제공한다. `null`, 빈 문자열, 공백도 같은 메서드 소스에 담는다.
- `@MethodSource` 메서드(`static`)는 그 값을 쓰는 테스트 바로 아래에 둔다. 여러 테스트가 같은 소스를 쓰면 처음 쓰는 테스트 바로 아래에 둔다.
- 케이스가 1개뿐이면 `@ParameterizedTest`를 쓰지 않고 `@Test`로 작성한다.

**이유**: 입력값이 테스트 바로 옆에 있어야 읽기 쉽고, `@ValueSource`와 `@NullAndEmptySource`를 나눠 쓰면 입력 목록이 여러 곳에 흩어진다. 
또한, 케이스 하나에 파라미터화를 쓰는 것은 과하다.

```java
// 좋은 예
@ParameterizedTest
@MethodSource("허용되지_않는_이미지_타입")
void 허용되지_않는_이미지_타입은_거부한다(String contentType) {
    // when
    boolean allowed = MediaPolicy.isAllowedImageContentType(contentType);

    // then
    assertThat(allowed).isFalse();
}

static Stream<String> 허용되지_않는_이미지_타입() {
    return Stream.of("image/gif", "video/mp4", "", " ", null);
}

// 나쁜 예: 입력값이 어노테이션 여러 개에 흩어지고, null·빈 값이 소스 메서드 밖에 있다
@ParameterizedTest
@ValueSource(strings = {"image/gif", "video/mp4"})
@NullAndEmptySource
void 허용되지_않는_이미지_타입은_거부한다(String contentType) { ... }
```

### 9.6 여러 필드 검증

**규칙**
- 한 객체의 필드를 여러 개 검증할 때는 `assertSoftly`로 한꺼번에 검증한다.
- `assertSoftly` 바로 앞에서 일반 `assertThat(객체).isNotNull()`로 객체가 null이 아닌지 먼저 확인한다.
- 단언이 하나뿐이면 일반 `assertThat`을 쓴다.

**이유**: 일반 `assertThat`은 첫 실패에서 멈춰서 틀린 필드를 하나씩만 알려 준다. 
`assertSoftly`는 틀린 필드를 한 번에 보여 줘서 원인 파악이 빠르다. 객체가 null이면 필드 검증이 의미 없고 NPE가 나므로 먼저 걸러 낸다.

```java
// 좋은 예
// then
assertThat(sut).isNotNull();
assertSoftly(softly -> {
    softly.assertThat(sut.getMemberId()).isEqualTo(memberId);
    softly.assertThat(sut.getType()).isEqualTo(WalletType.BUYER);
    softly.assertThat(sut.getBalance()).isZero();
});

// 나쁜 예: 첫 번째 단언이 실패하면 나머지는 확인되지 않는다
// then
assertThat(sut.getMemberId()).isEqualTo(memberId);
assertThat(sut.getType()).isEqualTo(WalletType.BUYER);
assertThat(sut.getBalance()).isZero();
```

### 9.7 예외 검증

**규칙**: 예외 검증은 AssertJ의 `assertThatThrownBy`로 통일한다. JUnit의 `assertThrows`와 `catchThrowable`은 쓰지 않는다.

**이유**: 검증 방식이 하나여야 읽기 쉽고, `assertThatThrownBy`는 호출과 검증이 한 문장에 있어 9.2의 `// when & then` 규칙과 맞는다.

```java
// 좋은 예
// when & then
assertThatThrownBy(() -> Wallet.create(null, WalletType.BUYER))
        .isInstanceOf(IllegalArgumentException.class);

// 나쁜 예: assertThrows, 또는 catchThrowable로 예외를 따로 받아 검증
assertThrows(IllegalArgumentException.class, () -> Wallet.create(null, WalletType.BUYER));
```

### 9.8 `RestApiException`의 에러 코드 확인

**규칙**: `RestApiException`은 `isInstanceOfSatisfying`으로 에러 코드를 확인한다. 메시지 문자열로 확인하지 않는다. `RestApiException`이 아닌 예외(`IllegalArgumentException` 등)는 `hasMessage`로 메시지를 확인한다.

**이유**: `e.getErrorCode()`를 코드로 직접 호출하므로 이름이 바뀌면 IDE가 함께 바꾸고, 사라지면 컴파일 단계에서 알 수 있다. `extracting("errorCode")`처럼 필드 이름을 문자열로 적으면 리팩토링 때 실행해 봐야 깨진 것을 안다. `detail` 문구는 바뀔 수 있어서 메시지가 아닌 에러 코드로 확인한다.

```java
// 좋은 예
// given
Cart cart = Cart.create(1L);

// when & then
assertThatThrownBy(() -> CartItem.create(cart, 1L, 1L, 0))
        .isInstanceOfSatisfying(RestApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_QUANTITY));

// 나쁜 예: 필드 이름을 문자열로 지정 (이름이 바뀌면 컴파일은 통과하고 실행해야 실패를 안다)
.isInstanceOf(RestApiException.class)
.extracting("errorCode").isEqualTo(OrderErrorCode.INVALID_QUANTITY);

// 나쁜 예: 메시지 문구로 확인 (문구가 바뀌면 깨진다)
.hasMessage("수량은 1개 이상이어야 합니다.");
```

### 9.9 테스트 클래스

**규칙**
- 테스트 클래스는 `src/test/java`에서 테스트 대상과 같은 패키지에 둔다.
- 테스트 클래스는 package-private(`class`)로 선언한다. `public`을 붙이지 않는다.
- 클래스 이름은 테스트 종류에 따라 접미사를 붙인다.
  - 도메인·서비스 단위 테스트: `*Test`
  - `AbstractIntegrationTest`를 상속한 통합 테스트: `*IntegrationTest`
  - `@ApplicationModuleTest` 모듈 테스트: `*ModuleTest`

**이유**: JUnit 5는 `public`을 요구하지 않으므로 붙이면 노출만 늘어난다. 접미사로 테스트 종류를 이름만 보고 알 수 있다.

```java
// 좋은 예
class WalletTest { ... }
class WalletServiceTest { ... }
class MemberSignupIntegrationTest extends AbstractIntegrationTest { ... }

@ApplicationModuleTest
class WalletModuleTest { ... }

// 나쁜 예: 불필요한 public, 통합 테스트인데 접미사가 *Test
public class WalletTest { ... }
class MemberSignupTest extends AbstractIntegrationTest { ... }
```

### 9.10 서비스 단위 테스트 작성 방침

**규칙**
- 결과를 검증한다: 반환값, 저장한 엔티티의 상태, 발행한 이벤트, 던진 예외. 저장한 값은 `ArgumentCaptor`로 확인한다.
- 호출 순서와 횟수(`verify(times)`)는 그 자체가 요구사항일 때(예: "이미 있으면 저장하지 않는다")만 검증한다.
- 저장소 쿼리 결과를 지나치게 세세하게 stub하지 않는다.

**이유**: 협력 객체를 Mockito로 대체한 테스트는 구현에 묶이기 쉽다. 결과 중심으로 검증하면 구현을 바꿔도(동작은 그대로일 때) 테스트가 깨지지 않는다.

```java
// 좋은 예: 저장한 값(결과)을 확인한다
@Test
void 지갑이_없으면_구매자_지갑을_생성한다() {
    // given
    given(walletRepository.existsByMemberIdAndType(1L, WalletType.BUYER)).willReturn(false);

    // when
    sut.createBuyerWallet(1L);

    // then
    ArgumentCaptor<Wallet> captor = ArgumentCaptor.forClass(Wallet.class);
    then(walletRepository).should().save(captor.capture());
    assertThat(captor.getValue().getType()).isEqualTo(WalletType.BUYER);
}

// 나쁜 예: 호출 횟수를 검증해 구현이 바뀌면 깨진다
verify(walletRepository, times(1)).existsByMemberIdAndType(1L, WalletType.BUYER);
verify(walletRepository, times(1)).save(any(Wallet.class));
```

### 9.11 DTO 테스트

**규칙**
- 로직이 없는 DTO(필드만 가진 `record`)는 테스트하지 않는다.
- 로직이 있는 DTO(값 정규화, `toCommand` 같은 변환, `toString` 마스킹)는 순수 단위 테스트로 검증한다. 같은 타입의 필드가 여러 개인 변환은 서로 다른 값을 넣고 모든 필드를 `assertSoftly`로 검증한다.
- `*Result` → `*Response` 매핑은 따로 테스트하지 않고, 통합 테스트에서 응답의 모든 필드를 확인한다.

**이유**: `record`는 필드를 추가하면 생성하는 모든 곳이 컴파일 오류가 나므로, 필드 누락은 컴파일러가 막는다. 컴파일러가 못 잡는 것은 같은 타입 필드의 순서 뒤바뀜(예: 모두 `String`인 `name`과 `nickname`)이고, 변환 테스트는 이것을 잡기 위해 쓴다.

```java
// 좋은 예: 필드마다 다른 값을 넣고 전부 확인해서 순서가 바뀐 변환을 잡는다
@Test
void 요청을_커맨드로_변환하면_모든_필드가_같은_값으로_옮겨진다() {
    // given
    SignupRequest sut = new SignupRequest(
            "tester01", "password1!", "tester@example.com", "홍길동", "길동이", true, true);

    // when
    SignupCommand command = sut.toCommand();

    // then
    assertThat(command).isNotNull();
    assertSoftly(softly -> {
        softly.assertThat(command.loginId()).isEqualTo("tester01");
        softly.assertThat(command.password()).isEqualTo("password1!");
        softly.assertThat(command.email()).isEqualTo("tester@example.com");
        softly.assertThat(command.name()).isEqualTo("홍길동");
        softly.assertThat(command.nickname()).isEqualTo("길동이");
    });
}

// 나쁜 예: 로직이 없는 record의 생성자와 접근자를 검증한다 (언어가 보장한다)
SignupResponse sut = new SignupResponse(1L);
assertThat(sut.memberId()).isEqualTo(1L);
```

### 9.12 외부 시스템 경계

**규칙**
- 외부 시스템(S3, 택배사 API 등) 연동은 우리가 정의한 인터페이스 뒤에 둔다. 인터페이스는 외부 SDK의 모양이 아니라 우리 도메인의 동작("파일을 저장한다", "배송을 요청한다")으로 정의한다.
- 통합 테스트에서는 외부 시스템을 호출하지 않고 `testsupport`의 Fake 구현으로 대체한다. Fake는 `AbstractIntegrationTest`에서 한 번만 등록해 모든 통합 테스트가 같은 컨텍스트를 공유하게 한다.
- 서비스 단위 테스트에서는 외부 인터페이스를 Mockito로 대체해도 된다(9.10).

**이유**: Fake는 호출 방식이 아니라 결과(저장된 상태)를 검증하게 하므로, 외부 SDK 호출 방식을 바꿔도 동작이 같으면 테스트가 깨지지 않는다. `@MockitoBean`은 대체 구성이 클래스마다 다르면 스프링 컨텍스트가 따로 떠서 느려진다.

```java
// 우리 도메인의 동작으로 정의한 인터페이스 (예시)
public interface FileStorage {
    String store(String objectKey, byte[] content, String contentType);   // 저장 후 접근 URL 반환
}

// testsupport의 Fake 구현 (예시): 메모리에 저장한다
public class FakeFileStorage implements FileStorage {
    private final Map<String, byte[]> stored = new HashMap<>();

    @Override
    public String store(String objectKey, byte[] content, String contentType) {
        stored.put(objectKey, content);
        return "http://fake-storage/" + objectKey;
    }

    public boolean contains(String objectKey) {
        return stored.containsKey(objectKey);
    }
}

// 좋은 예: 결과(저장 여부)를 확인한다
assertThat(fakeFileStorage.contains(objectKey)).isTrue();

// 나쁜 예: 외부 SDK 호출 방식에 묶인다
verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
```

## 10. 코드 포맷

### 10.1 import

**규칙**: 와일드카드 import(`*`)를 쓰지 않고 클래스를 하나씩 import한다. IntelliJ에서는 Imports 설정의 `Class count to use import with '*'`와 `Names count to use static import with '*'` 값을 크게 올려서 자동으로 `*`로 합치지 않게 한다(설정 이름은 IntelliJ 버전에 따라 다를 수 있다).

**이유**: import는 컴파일 시점에 이름을 해석하는 것이라 `*`를 써도 런타임 성능에 영향이 없다. 하지만 어떤 클래스를 쓰는지 한눈에 보이지 않아 가독성과 유지보수가 떨어지고, 같은 이름의 클래스가 여러 패키지에 있으면 이름이 충돌할 수 있다.

```java
// 좋은 예
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

// 나쁜 예
import jakarta.persistence.*;
```

### 10.2 파일 끝

**규칙**: 파일은 마지막 줄 뒤에 개행 한 줄로 끝낸다. 개행이 없거나 빈 줄이 여러 개 이어지지 않게 한다.

**이유**: 개행이 없으면 diff에 "No newline at end of file"이 표시되고, 파일을 이어 붙일 때 마지막 줄이 다음 내용과 붙는다.

### 10.3 빈 줄

**규칙**: 빈 줄을 연속해서 두지 않는다(한 줄까지만). `{` 바로 다음이나 `}` 바로 앞의 빈 줄은 허용한다.

**이유**: 구분을 위한 빈 줄은 읽기 쉽게 하므로 허용하고, 과한 여백만 막는다. 블록의 시작과 끝까지 막는 것은 지키기 불편하다.

### 10.4 주석

**규칙**
- 주석은 한국어로 쓴다.
- 코드를 보면 알 수 있는 **무엇**이 아니라, 코드만으로 알기 어려운 **왜**(이유, 제약, 결정)를 쓴다.
- 클래스와 공개 메서드의 설명은 Javadoc(`/** */`), 메서드 안의 설명은 `//`로 쓴다. Javadoc 작성 방법은 10.6을 본다.

**이유**: "왜"를 쓰는 주석은 코드가 바뀌어도 낡지 않는다. 코드를 그대로 옮긴 주석은 코드가 바뀔 때 어긋나기 쉽다. Javadoc은 IDE가 도움말로 보여 주므로 클래스와 공개 메서드 설명에 맞다.

```java
// 좋은 예: 클래스 설명은 Javadoc (MemberRegisteredEvent)
/**
 * 회원가입 완료 이벤트. 다른 모듈은 이 이벤트를 구독해 후속 처리를 한다.
 */
public record MemberRegisteredEvent(Long memberId, MemberRole memberRole) { }

// 좋은 예: 왜 이렇게 하는지를 쓴다 (PayoutService)
// 판매자는 뽑혔는데 후보가 0건 = 두 조회 조건 불일치(버그). 그대로 두면 같은 판매자가 계속 뽑혀 무한 반복되므로 실패시킴
if (candidates.isEmpty()) { ... }

// 나쁜 예: 코드를 보면 아는 내용을 적는다 (MemberSignupService)
// 회원 저장
Member saved = save(member);
```

### 10.5 TODO

**규칙**
- 형식은 `// TODO(#이슈번호): 할 일과 이유`로 쓴다.
- 해야 할 일과 함께 이유나 현재 상태를 적어서, 읽는 사람이 무슨 일인지 알 수 있게 한다.
- 이슈 번호가 아직 없으면 이슈를 만든 뒤 번호를 적는다. 팀 논의가 필요해 이슈로 만들기 어려운 경우에는 번호 대신 `TODO(논의 필요)`로 쓰고 무엇을 논의해야 하는지 적는다.
- TODO를 해결하면 같은 PR에서 주석을 지운다.

**이유**: 이슈 번호가 있어야 TODO를 추적하고 잊지 않을 수 있다. 할 일만 적으면 나중에 왜 남겼는지 알 수 없다. 해결한 TODO를 남겨 두면 코드와 어긋난 주석이 된다.

```java
// 좋은 예: 이슈 번호 + 할 일 + 현재 상태 (PayoutJobRunner)
// TODO(#52): 실행 기록은 현재 메모리 저장 (batch-jdbc 미적용), DB 저장 여부 논의 필요

// 좋은 예: 이슈로 만들기 어려운 논의 사항
// TODO(논의 필요): 원 미만 처리 방식(버림/반올림/올림) 팀 확정 필요. 현재는 버림 기준.

// 나쁜 예: 이슈 번호도 시점도 없다 (PayoutController)
// TODO: API 작업 시 응답 DTO 위치·형식 정리
```

### 10.6 Javadoc 작성 방법

**규칙**
1. 첫 문장은 무엇을 하는지 한 문장으로 요약하고 "~한다"로 끝낸다. 클래스는 맡은 책임을 한 문장으로 쓴다.
2. 첫 문장으로 부족한 내용(왜 그렇게 하는지, 제약, 이벤트 발행 같은 부수 효과)은 빈 줄 없이 다음 줄에 이어서 쓴다.
3. 태그는 필요한 것만 쓴다.

| 태그 | 쓰는 경우 |
|---|---|
| `@param` | 이름만으로 의미, 단위, 제약을 알기 어려운 매개변수만 쓴다. 모든 매개변수를 쓸 필요는 없다 |
| `@return` | 반환값의 의미가 이름만으로 분명하지 않을 때 쓴다 |
| `@throws` | 호출하는 쪽이 알아야 하는 예외를 쓴다. `RestApiException`은 던지는 에러 코드 이름을 적는다 |

4. 쓰지 않아도 되는 곳: getter, 이름만으로 충분한 메서드, `@Override`한 메서드, private 메서드.
5. 코드나 이름은 `{@code ...}`로, 다른 클래스를 가리킬 때는 `{@link ...}`로 쓴다.

**이유**: 이름을 되풀이할 뿐인 태그를 모두 채우면 읽는 사람에게 도움이 되지 않고 코드가 바뀔 때 어긋난다. 필요한 정보만 쓰면 작성하는 부담도 줄고 읽기 쉽다. IntelliJ에서는 `/**`를 입력하고 Enter를 누르면 틀이 자동으로 만들어지므로, 필요 없는 태그를 지우면 된다.

```java
// 템플릿: 필요 없는 줄은 지운다
/**
 * 무엇을 하는지 한 문장으로 요약한다.
 * 필요하면 왜 그렇게 하는지, 제약이나 부수 효과를 이어서 쓴다.
 *
 * @param 매개변수 이름만으로 알기 어려운 의미나 단위 (필요할 때만)
 * @return 반환값의 의미 (필요할 때만)
 * @throws RestApiException 던지는 에러 코드 (호출하는 쪽이 알아야 할 때만)
 */

// 좋은 예: 알기 어려운 매개변수에만 @param을 쓴다 (Member.createBuyer, 일부 생략)
/**
 * 일반 회원(구매자)을 생성한다.
 * 이용약관·개인정보 수집·이용 동의는 요청 검증 단계에서 확인된 상태여야 하며, 두 동의 일시는 agreedAt으로 기록한다.
 *
 * @param encodedPassword 인코딩된 비밀번호 (평문 금지)
 * @param agreedAt        약관 동의 일시
 */

// 좋은 예: 던지는 에러 코드를 적는다 (예시)
/**
 * 회원가입을 처리한다.
 *
 * @throws RestApiException 아이디나 이메일이 중복이면 {@code DUPLICATE_LOGIN_ID}, {@code DUPLICATE_EMAIL}
 */

// 나쁜 예: 이름을 되풀이하는 태그를 모두 채운다
/**
 * 회원을 생성한다.
 *
 * @param loginId 로그인 아이디
 * @param email 이메일
 * @param name 이름
 */
```

## 11. Git·협업

### 11.1 커밋 메시지

**규칙**
- 아래 형식으로 쓴다. 제목과 본문, 본문과 꼬리말은 각각 빈 줄로 나눈다.

```
<Type>: <제목>

- 변경 내용 1
- 변경 내용 2

Ref: #이슈번호
```

- 커밋 유형(`Type`)은 영어 대문자로 시작하고 아래에서 고른다.

| 커밋 유형 | 의미 |
|---|---|
| `Feat` | 새로운 기능 추가 |
| `Fix` | 버그 수정 |
| `Docs` | 문서 수정 |
| `Style` | 코드 formatting, 세미콜론 누락 등 코드 자체의 변경이 없는 경우 |
| `Refactor` | 코드 리팩토링 |
| `Test` | 테스트 코드, 리팩토링 테스트 코드 추가 |
| `Chore` | 패키지 매니저 수정, 그 외 기타 수정(예: `.gitignore`) |
| `Design` | CSS 등 사용자 UI 디자인 변경 |
| `Comment` | 필요한 주석 추가 및 변경 |
| `Rename` | 파일 또는 폴더 이름을 수정하거나 옮기는 작업만인 경우 |
| `Remove` | 파일을 삭제하는 작업만 수행한 경우 |
| `!BREAKING CHANGE` | 큰 API 변경의 경우 |
| `!HOTFIX` | 급하게 치명적인 버그를 고쳐야 하는 경우 |

- 유형 이후의 제목과 본문은 한글로 쓴다. 본문에는 변경한 내용과 이유(어떻게보다 무엇과 왜)를 쓴다. 자신의 코드가 직관적으로 파악된다고 생각하지 않는다.
- 제목은 끝에 `.`을 쓰지 않고, 영문 기준 50자 이내로 쓴다.
- 여러 항목은 글머리 기호(`-`)로 쓴다.
- 이슈 번호는 본문 아래 꼬리말에 `Ref: #이슈번호` 형식으로 쓴다. 관련 이슈가 여러 개면 한 줄에 하나씩 쓴다. 제목 끝의 `(#N)`은 GitHub이 병합할 때 붙이는 PR 번호이므로 직접 쓰지 않는다.
- 커밋은 변경의 성격별로 나눈다. `.env`, 비밀번호, 토큰은 커밋하지 않는다(`.env.example`만 올린다).

**이유**: 팀원과 소통하고, 과거 변경을 편리하게 추적하고, 나중에 실무에 익숙해지기 위해서다.

```
// 좋은 예
Feat: 회원가입 시 이메일 인증 기능 추가

- 인증 코드 발송 API 추가
- 인증 코드 입력 화면 추가

Ref: #1
Ref: #2

// 나쁜 예: 유형이 소문자이다
feat: 주문→정산 구매확정 이벤트 DTO 정의

// 나쁜 예: 유형 대신 도메인을 대괄호로 쓰고, 제목 끝에 PR 번호를 직접 썼다
[지갑] Wallet 도메인 및 회원가입 시 지갑 생성 구현 (#51)
```

### 11.2 브랜치 전략

**규칙**
- 브랜치는 `main`과 `dev`를 둔다. `dev`는 개발 환경에서 쓰고, `main`은 운영에서 실행하는 코드다. `main`과 `dev`에 직접 커밋하지 않는다.
- 개발은 다음 순서로 진행한다.
  1. 이슈를 만든다.
  2. `dev`에서 이슈별 브랜치를 만든다.
  3. 기능을 구현하고 `dev`로 PR을 보내 병합한다.
  4. `dev`에 모인 코드를 `main`으로 PR을 보내 병합한다.
- 브랜치 이름은 `<유형>/<이슈번호>`로 쓴다. 기능 구현은 `feature/`를 쓰고, 나머지는 11.1의 커밋 유형을 소문자로 쓴다. 이슈의 `type` 라벨과 같은 체계다.

| 브랜치 유형 | 커밋 유형 | 이슈 라벨 | 예 |
|---|---|---|---|
| `feature/` | `Feat` | `type: feat` | `feature/40` (예시) |
| `fix/` | `Fix` | `type: bug` | `fix/45` |
| `docs/` | `Docs` | `type: docs` | `docs/60` |
| `refactor/` | `Refactor` | `type: refactor` | `refactor/62` |
| `test/` | `Test` | `type: test` | `test/70` (예시) |
| `chore/` | `Chore` | `type: chore` | `chore/71` (예시) |
| `design/` | `Design` | `type: design` | `design/72` (예시) |

**이유**: 기능 브랜치와 `dev`에서 개발하고 확인한 변경만 `main`으로 올려서, 운영에서 실행하는 코드를 안정적으로 유지한다.

```
feature/40 ─┐
fix/45     ─┼─ ③ PR → dev ─ ④ PR → main
docs/60    ─┘   (이슈별 브랜치)    (dev에 모인 코드)
```

### 11.3 PR과 이슈

**규칙**
- 이슈는 `.github/ISSUE_TEMPLATE/`의 템플릿으로 만들고, PR은 `.github/PULL_REQUEST_TEMPLATE.md`를 따라 쓴다.
- PR의 리뷰어로 해당 기능과 연관된 도메인의 담당자를 추가한다.
- AI 봇의 리뷰가 OK이면 squash merge로 병합한다.

> AI 봇의 OK를 병합 조건으로 쓸 수 있는지는 확인이 필요하다. 현재 코드 리뷰 워크플로(`claude-code-review.yml`)는 PR에 댓글만 남기고, 승인이나 병합 차단은 하지 않는다.

**이유**: 연관된 도메인의 담당자가 변경의 영향 범위를 가장 잘 안다. squash merge로 병합하면 PR 하나가 커밋 하나로 남아 이력이 단순해진다.

## 머지 전 확정할 항목

팀과 협의해 확정한 뒤, 이 절을 지우고 본문에 반영한다.

| 항목 | 현재 본문 | 확정할 내용 | 협의 상대 |
|---|---|---|---|
| `common`에 두는 코드의 기준 (2.4) | 둘 이상의 모듈이 쓰는 코드만 `common`에 둔다 | `S3Config`와 `MediaInfo`는 media만 쓰는데 `common`에 있다. 본문 초안은 "각 모듈이 S3 설정을 따로 가져가고, 공통으로 묶을 규칙이 생기면 그때 `common`으로 옮긴다"이다. 다만 이슈 #7에 "각 도메인에서 공통 API로 업로드한 미디어를 참조할 수 있도록 한다"고 적혀 있어, 다른 모듈은 S3를 직접 쓰지 않고 media의 업로드 API를 쓰고 `mediaId`만 저장하는 설계일 수 있다. media 담당에게 확인이 필요하다 | media 담당, 환불·회원 담당 |
| 공개 이벤트의 위치 (2.2) | 공개 이벤트는 발행 모듈의 최상위 패키지에 둔다 | `OrderProductConfirmedEvent`는 `common.event`에 있다(order 모듈이 아직 없음). order 모듈이 생길 때의 위치 | order 담당, payout 담당 |
| `DEVELOPMENT.md`의 모듈 표 | 표에 `media`가 없다 | media를 도메인 모듈로 볼지 공통 모듈로 볼지(이슈 #7의 라벨은 `domain: common`) | 팀 전체 |
| 브랜치 유형 접두사 (11.2) | 기능은 `feature/<이슈번호>`, 나머지는 커밋 유형을 소문자로 쓴 `<유형>/<이슈번호>` | `bug` 라벨을 `fix/`로 대응하는 것, epic 통합 브랜치 이름(현재 `feature/41`) | 팀 전체 |
| AI 봇 병합 조건 (11.3) | AI 봇의 리뷰가 OK이면 squash merge | 현재 리뷰 워크플로는 댓글만 남기고 승인·차단을 하지 않는다. OK를 병합 조건으로 쓰는 방법 | 팀 전체 |
| 이슈 템플릿과 라벨 | 이슈 템플릿 사용 | 템플릿의 `labels:`(`docs`, `bug`, `feature`, `refactor`)가 실제 라벨(`type: docs`, `type: bug`, `type: feat`, `type: refactor`)과 다르다. 도메인 라벨과 모듈 이름의 대응(`settlement` / `payout`, `media` 라벨 없음), 이슈에 유형 라벨을 필수로 붙일지 | 팀 전체 |
| 문서 운영 | 정하지 않음 | `docs/`(팀 공용)와 `data/arch/`(개인)의 구분 기준, 문서 파일명 규칙, 코드 변경 시 문서 갱신 책임, API 명세의 원천(Swagger / 마크다운) | 팀 전체 |

### 엔티티 (4절의 나머지는 팀 협의 후 작성)

공통 부모(4.1)와 삭제 방식(4.2)은 정했고, 나머지는 엔티티를 작성하는 방식이 모듈마다 달라서 규칙을 정하지 않았다. 협의로 확정할 쟁점과 현재 상태는 다음과 같다.

| 쟁점 | 현재 상태 | 협의 상대 |
|---|---|---|
| `BaseEntity`의 역할 | `BaseEntity`는 `getId()`, `getCreatedAt()`, `getUpdatedAt()`을 추상 메서드로 선언하고 MarketMember만 상속한다. 4.1처럼 ID를 엔티티마다 정의하면 `getId()` 강제가 맞는지, `BaseTime`으로 합칠지 | order 담당 |
| `BaseTime` 전환의 영향 | Media는 부모 없이 id와 `created_at`을 직접 선언하고 수정 시각이 없다. `BaseTime`을 쓰면 `updated_at`이 생겨 마이그레이션이 필요하다. MarketMember는 시각 필드를 직접 선언하고 있다 | media 담당, order 담당 |
| 정적 팩토리 이름 | `create`(Cart, CartItem, MarketMember, Payout, Wallet), `of`(Media), `createBuyer`(Member), `ofSaleFee` 등(PayoutCandidateItem) | 각 모듈의 엔티티 담당 |
| 생성 시 검증 | Wallet은 생성자에서 `IllegalArgumentException`, CartItem은 `create`에서 `RestApiException`, Member는 검증이 없다(요청 DTO에서 검증했다고 가정). 6.6의 "불변식은 생성자에서 검증"과 Member가 다르다 | 각 모듈의 엔티티 담당 |
| `@Column` 표기 | Wallet, Media는 `name`을 항상 명시, Member는 모두 생략(네이밍 전략에 의존), CartItem은 섞여 있다 | 각 모듈의 엔티티 담당 |

## 향후 개선 사항

지금은 규칙으로 정하지 않고, 필요해졌을 때 개선하기로 보류한 항목이다.

### 통합 테스트의 데이터 정리 방식

- **현재**: 통합 테스트는 자동 롤백이 없어서, 정리가 필요한 테스트가 스스로 데이터를 정리한다(예: `@AfterEach`에서 `deleteAllInBatch()`).
- **보류 이유**: 현재 테스트 작성에 크게 영향을 주지 않는다.
- **개선할 때**: 테스트 검증 시간이 기능 구현에 문제가 될 정도로 길어졌을 때.
- **검토한 방향**: 테스트가 빈 DB를 가정하지 않도록 고유한 데이터를 쓰고, 전체 상태에 영향받는 테스트만 `@BeforeEach`에서 해당 테이블을 정리한다. 공통 헬퍼는 자동 실행 없이 도구로만 제공한다.

### 서비스의 읽기·쓰기 분리

- **현재**: 읽기와 쓰기를 한 `*Service`에 둔다(3.5).
- **보류 이유**: 지금은 조회 API가 거의 없어서 나눌 대상이 없다.
- **개선할 때**: 서비스가 커져서 읽기와 쓰기를 함께 두기 어려워졌을 때.
- **검토한 방향**: `*CommandService`와 `*QueryService`로 나눈다.
