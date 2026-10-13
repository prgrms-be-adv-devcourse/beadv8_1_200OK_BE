---
paths:
  - "src/main/java/**/exception/**/*.java"
---

# 예외·에러 코드

예외를 던지거나 에러 코드를 만들 때 따르는 규칙이다.
사람용 원문: `docs/CONVENTION.md` 6.1~6.5.

## 예외 종류

- 클라이언트에 오류 응답으로 나가는 경우(사용자 입력 오류, 도메인 규칙 위반)는 `RestApiException`에 모듈의 `ErrorCode`를 담아 던진다.
- 응답이 없는 곳(이벤트 리스너, 배치)에서도 도메인 규칙이나 모듈 간 계약을 어기면 `RestApiException`을 쓴다.
- 호출한 코드의 실수(필수 인자에 `null`을 넘김 등)는 표준 예외(`IllegalArgumentException` 등)를 던진다.

**이유**: 응답으로 나가는 오류는 에러 코드로 유형을 구분해야 클라이언트가 분기할 수 있다. 이벤트로 받은 값이 계약과 다른 것도 모듈 간 계약 위반이라 유형을 구분해 둔다. 호출한 코드의 실수는 사용자에게 보일 오류가 아니라 개발 중 고칠 버그이고, 처리되지 않은 예외로 500이 된다.

```java
// O
throw new RestApiException(OrderErrorCode.INVALID_QUANTITY);
throw new RestApiException(PayoutErrorCode.MISSING_ORDER_ITEM_ID);   // 이벤트 계약 위반
throw new IllegalArgumentException("memberId는 필수입니다.");          // 호출한 코드의 실수

// X: 사용자 입력 오류에 표준 예외 (유형 구분 불가, 500이 된다)
throw new IllegalArgumentException("수량은 1개 이상이어야 합니다.");
```

## 에러 코드

- 모든 모듈은 자기 `*ErrorCode` enum(`ErrorCode` 구현)을 만들고, 도메인 오류는 그 enum으로 던진다.
- enum은 모듈의 `exception` 패키지에 둔다(`com.ok.order.exception.OrderErrorCode`).
- `CommonErrorCode`는 입력 검증 실패, 잘못된 JSON, 용량 초과 같은 HTTP 수준 공통 오류에만 쓴다. 도메인 오류에 `CommonErrorCode`와 직접 쓴 메시지를 조합하지 않는다.

**이유**: 오류 유형을 에러 코드로 구분해야 클라이언트가 분기할 수 있다. 하나의 코드에 메시지만 바꿔 쓰면 빈 파일, 미지원 형식처럼 서로 다른 오류가 같은 유형이 된다.

```java
// O
throw new RestApiException(MediaErrorCode.EMPTY_FILE);
throw new RestApiException(MediaErrorCode.UNSUPPORTED_MEDIA_TYPE);

// X: 같은 코드에 메시지만 바꿔 유형이 구분되지 않는다
throw new RestApiException(CommonErrorCode.BAD_REQUEST, "빈 파일은 업로드할 수 없습니다.");
```

## 에러 코드 작성

- enum은 `ErrorCode`를 구현하고, 항목마다 HTTP 상태, `title`, 기본 `detail`(`message`)을 둔다.
- `detail`에는 응답으로 내보내도 되는 값만 넣는다(요청에서 받은 값, 식별자 정도). 내부 예외 메시지나 SQL 오류를 넣지 않는다.

**이유**: `detail`은 클라이언트에 그대로 전달되므로 내부 구현 정보가 새어 나가지 않게 한다.

```java
// O
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

// X
throw new RestApiException(OrderErrorCode.ORDER_NOT_FOUND, e.getMessage());   // 내부 메시지 노출
```

## `title`

- 영어로, 첫 글자만 대문자인 짧은 문장으로 쓴다(`Member not found`).
- 값을 끼워 넣지 않고 유형별로 고정한다. 한국어 설명은 `detail`(`message`)에 쓴다.

**이유**: `title`은 오류 유형의 짧은 이름이라 유형마다 고정되어야 하고, 상황에 따라 달라지는 설명은 `detail`이 맡는다. 표기를 맞추면 응답이 일관된다.

```java
// O
MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "Member not found", "회원 정보를 찾을 수 없습니다."),

// X
CONTENT_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "content too large", "..."),            // 소문자 시작
MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원 123을 찾을 수 없습니다", "..."),             // 한국어, 값 포함
```

## 에러 응답의 `type` 주소(slug)

- 모듈 에러 코드의 slug는 `<모듈>/<에러 코드 이름>` 형식이다. 이름은 enum 이름을 소문자와 하이픈으로 바꾼 값이다(`MEMBER_NOT_FOUND` → `member-not-found`).
- `CommonErrorCode`는 접두사 없이 쓴다(`validation-failed`, `bad-request`).
- 배포한 뒤에는 `type`을 바꾸지 않는다.

**이유**: 모듈마다 같은 이름의 에러 코드가 생겨도 `type`이 겹치지 않고, 클라이언트가 `type`만 보고 어느 모듈의 오류인지 안다. 클라이언트가 분기에 쓰는 값이라 배포한 뒤에는 바꿀 수 없으므로 처음부터 정한다.

```
// O ({base}는 PROBLEM_BASE_URI)
{base}/member/duplicate-email     MemberErrorCode.DUPLICATE_EMAIL
{base}/order/member-not-found     OrderErrorCode.MEMBER_NOT_FOUND
{base}/validation-failed          CommonErrorCode.VALIDATION_FAILED

// X: 모듈 접두사가 없다
{base}/member-not-found
```
