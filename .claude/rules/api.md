---
paths:
  - "src/main/java/**/controller/**/*.java"
---

# DTO·API

컨트롤러와 요청·응답 DTO를 작성할 때 따르는 규칙이다. 예외와 에러 코드는 `exception.md`를 본다.
사람용 원문: `docs/CONVENTION.md` 2.3, 3.3, 3.4, 5절, 6.6.

## URL

- 경로는 `/api/v1`로 시작한다.
- 리소스 이름은 복수형 명사로 쓴다(`/members`). `media`처럼 복수형이 따로 없으면 그대로 쓴다.
- 생성, 조회, 수정, 삭제는 HTTP 메서드로 표현한다.
- 업무 처리 성격의 행위(회원가입, 로그인, 정산 실행 등)는 리소스 아래 동사 하위 경로를 허용한다.

**이유**: 복수형 명사와 HTTP 메서드의 조합은 가장 널리 쓰는 REST 관례라 API를 예측하기 쉽다. 로그인이나 정산 실행처럼 리소스 생성·수정으로 표현하기 어색한 행위까지 명사로 바꾸면 오히려 읽기 어렵다.

```
// O
POST /api/v1/members/signup
POST /api/v1/media/{purpose}
GET  /api/v1/products/{productId}

// X
POST /api/v1/payout/run          단수형
GET  /api/v1/getProduct          조회를 URL에 쓴다
```

## 멱등성

- 멱등키는 선택 사항이다. 멱등하지 않은 메서드(주로 `POST`)인데 멱등성이 필요한 API에서만 쓴다.
- 클라이언트가 보내는 멱등키를 요청 헤더 `Idempotency-Key`로 받는다.
- 같은 멱등키로 다시 요청하면 처리를 반복하지 않고 첫 요청의 결과를 돌려준다.
- 공통 구현은 아직 없다. 필요한 API가 생길 때 추가한다.
- 이벤트 처리의 멱등성은 이 규칙이 아니라 유니크 제약과 존재 확인으로 처리한다.

**이유**: 네트워크 재시도나 중복 클릭으로 같은 요청이 두 번 와도 한 번만 처리되어야 한다(예: 주문 중복 생성 방지). 멱등키가 있어야 본문이 같은 별개의 요청과 구분된다.

## Swagger 문서화

- 요청·응답 DTO의 모든 필드에 `@Schema(description = ...)`를 쓴다. `example`은 형식이 정해져 있거나 값의 모양이 중요한 필드에만 쓴다.
- 컨트롤러 Spec에는 성공 응답과 해당 API가 실제로 반환하는 주요 오류를 `@ApiResponse`로 적는다.
- 오류 응답 JSON 예시는 클라이언트가 분기해야 하는 오류(예: 409 중복)에만 쓴다.

**이유**: 모든 필드에 설명이 있어야 Swagger만 보고 API를 쓸 수 있다. 모든 오류에 예시를 강제하면 Spec이 지나치게 길어져 유지하기 어렵다.

```java
// O
@Schema(description = "로그인 아이디 (영문, 숫자 4~20자)", example = "tester01")
String loginId,

@ApiResponse(responseCode = "201", description = "회원가입 성공")
@ApiResponse(responseCode = "409", description = "아이디 또는 이메일 중복")
@ApiResponse(responseCode = "422", description = "요청 값 검증 실패")

// X
public record MediaInfo(long mediaId, String url) { }   // 필드 설명이 없다
@ApiResponse(responseCode = "201", description = "업로드 성공")   // 성공만 적고 오류가 없다
```

## 값 정규화·변환

- 형식 정규화(공백 제거, 대소문자 통일)는 요청 DTO의 컴팩트 생성자에서 한다. 검증은 정규화된 값 기준으로 수행된다.
- 공백이 값의 일부일 수 있는 필드(비밀번호 등)는 정규화하지 않는다.
- 요청 DTO → `*Command` 변환은 요청 DTO의 `toCommand()`에서 한다.
- 비즈니스 로직에 속하는 값 가공(비밀번호 암호화, 시각 기록, 상태 결정)은 서비스에서 한다.

**이유**: 형식 정규화는 형식 검증과 짝이라 같은 곳(DTO)에 둔다. 서비스에서 정규화하면 정규화 전 값이 먼저 검증에 걸린다(대문자 아이디가 소문자 패턴에서 거절됨). 값을 가공해 저장하는 일은 비즈니스 결정이라 서비스가 맡는다.

```java
// O
public SignupRequest {
    loginId = toLowerCaseTrimmed(loginId);
    name = trim(name);
}
public SignupCommand toCommand() { return new SignupCommand(loginId, password, email, name, nickname); }

// 서비스
String encodedPassword = passwordEncoder.encode(command.password());

// X: 서비스에서 정규화한다 (정규화 전 값이 먼저 형식 검증에 걸린다)
String loginId = command.loginId().toLowerCase();
```

## 검증 위치

- 요청 형식 검증(필수값, 길이, 패턴, 범위)은 요청 DTO의 Bean Validation으로 하고, 컨트롤러에서 `@Valid`로 검증한다.
- 비즈니스 규칙 검증(중복 여부, 상태, 정책)은 서비스나 도메인(엔티티, 정책)에서 한다.
- 엔티티·값 객체의 불변식은 생성자나 정적 팩토리에서 검증한다. 위반 값이 사용자 입력에서 올 수 있으면 `RestApiException`, 호출한 코드의 실수라면 표준 예외를 던진다.

**이유**: 요청 형식 오류는 서비스에 닿기 전에 422로 거절하고 필드별 오류를 돌려줄 수 있다. 비즈니스 규칙은 요청 형식과 무관하게 지켜야 해서 서비스·도메인이 맡고, 불변식을 객체가 스스로 검증하면 어떤 경로로 만들어도 유효한 객체만 존재한다.

```java
// O: 요청 형식은 DTO
@NotBlank(message = "아이디는 필수입니다.")
@Pattern(regexp = "^[a-z0-9]{4,20}$", message = "아이디는 영문과 숫자로 4~20자여야 합니다.")
String loginId

// O: 비즈니스 규칙은 서비스(검증기)
if (memberRepository.existsByLoginId(loginId)) {
    throw new RestApiException(MemberErrorCode.DUPLICATE_LOGIN_ID);
}

// O: 불변식은 엔티티 생성자
private Wallet(Long memberId, WalletType type) {
    if (memberId == null) {
        throw new IllegalArgumentException("memberId는 필수입니다.");
    }
}

// X: 호출하는 쪽에서만 확인하고 생성자에는 검증이 없다
```

## DTO 위치

- 요청·응답 DTO(`*Request`, `*Response`)는 `controller/dto`에 둔다.
- DTO를 컨트롤러 안의 중첩 `record`로 두지 않는다.
- 서비스는 `*Response`를 반환하지 않으므로, 컨트롤러가 서비스의 반환값(식별자, `*Result`)으로 `*Response`를 만든다.
- 컨트롤러와 `controller/dto`는 `domain` 패키지의 타입(엔티티, enum 등)을 쓰지 않는다.

**이유**: 패키지로 나누면 어느 계층의 타입인지 경로만 보고 안다. 중첩 `record`는 컨트롤러 파일을 키우고 다른 곳에서 찾기 어렵다. 웹 응답 타입은 프레젠테이션 레이어의 책임이다. 컨트롤러가 엔티티를 알면 엔티티 변경이 API 응답까지 번진다. `ArchitectureTest`가 검사한다.

```java
// O
Long memberId = memberSignupService.signup(command);
return ResponseEntity.status(HttpStatus.CREATED).body(new SignupResponse(memberId));

return ResponseEntity.ok(CartResponse.from(cartService.getMyCart(memberId)));   // CartResult → CartResponse

// X
public class PayoutController {
    public record PayoutRunResponse(Long executionId, String status) { }   // 컨트롤러 안의 중첩 record
}
public static CartResponse of(Cart cart, List<CartItem> items) { ... }    // 응답 DTO가 엔티티를 받는다
```

## Swagger 문서용 인터페이스 (`*Spec`)

- 이름은 **대상 클래스 이름 + `Spec`**, **package-private**, 대상과 **같은 패키지**에 둔다.
- 별도 `spec` 패키지를 만들지 않고, `sealed`로 구현을 강제하지 않는다.

**이유**: 문서 어노테이션만 컨트롤러와 분리하고 다른 곳에서는 쓰지 않으므로 package-private으로 둔다. `sealed`는 인터페이스가 구현체를 알아야 해서 문서가 구현을 가리키는 역방향 의존이 생기고, 구현 클래스가 `final`이어야 하는 제약도 따른다.

```java
// O
@Tag(name = "Media", description = "미디어 업로드 API")
interface MediaControllerSpec { ... }

public class MediaController implements MediaControllerSpec { ... }

// X
public interface MemberControllerDocs { ... }
com.ok.media.controller.spec.MediaApiSpec
```

## 컨트롤러 이름

- 이름에 API 버전 접두사(`ApiV1` 등)를 붙이지 않는다. 버전은 URL(`/api/v1/...`)에만 둔다.

**이유**: 버전은 URL이 이미 담고 있어 이름에 또 적으면 중복이고, `*Controller`·`*Spec` 접미사 규칙과도 어긋난다.

```java
// O
@RequestMapping("/api/v1/members")
class MemberController { ... }

// X
class ApiV1MemberController { ... }
```
