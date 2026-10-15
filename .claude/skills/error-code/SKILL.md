---
name: error-code
description: 모듈의 ErrorCode enum에 에러 코드를 추가하거나 새 모듈의 *ErrorCode를 만들고, RestApiException으로 던지는 코드까지 작성한다. 도메인 오류·예외 응답을 새로 정의할 때 사용한다.
---

# 에러 코드 추가

규칙의 상세 내용은 `.claude/rules/exception.md`(원문 `docs/CONVENTION.md` 6절)를 따른다. 이 스킬은 작성 절차다.

## 절차

1. **예외 종류를 값의 출처로 정한다.** 호출 경로(HTTP, 이벤트, 배치)로 정하지 않는다.
   - 사용자 입력이 닿을 수 있는 값의 오류(입력값 오류, 도메인 규칙 위반, 상태 충돌) → `RestApiException` + 모듈의 `*ErrorCode`
   - 다른 모듈이나 시스템이 계산해서 넘긴 값의 오류(이벤트·배치가 받은 값 포함) → `IllegalArgumentException` 등 표준 예외. 이 스킬 대상이 아니다.
2. **모듈의 `*ErrorCode`를 찾는다.**
   ```bash
   find src/main -name '*ErrorCode.java'
   ```
   없으면 모듈의 `exception` 패키지에 새로 만든다(`ErrorCode` 구현). 기존 파일이 `domain` 등 다른 패키지에 있어도 옮기지 않고 그 파일에 추가한다.
3. **이미 같은 의미의 코드가 있는지 확인한다.** 있으면 재사용한다. `CommonErrorCode`에 직접 쓴 메시지를 조합해 도메인 오류를 만들지 않는다.
4. **항목을 추가한다.** 아래 형식을 따르고, 같은 주제의 항목 근처에 둔다.
5. **던지는 코드를 작성한다.** 기본 `detail`을 쓰거나, 응답으로 내보내도 되는 값(식별자 등)만 넣어 지정한다.
6. **테스트한다.** 에러 코드는 메시지가 아니라 `isInstanceOfSatisfying`으로 확인한다(`.claude/rules/testing.md`).

## 항목 형식

```java
ORDER_ALREADY_PAID(HttpStatus.CONFLICT, "Order already paid", "이미 결제된 주문입니다."),
```

- 이름: `UPPER_SNAKE_CASE`
- `title`: 영어, 첫 글자만 대문자인 짧은 문장, 값을 끼워 넣지 않는다.
- `message`(기본 `detail`): 한국어. 내부 예외 메시지나 SQL 오류를 넣지 않는다.
- HTTP 상태: 입력 오류 `400`/`422`, 없음 `404`, 중복·상태 충돌 `409`, 권한 `403`

## 던지는 코드

```java
throw new RestApiException(OrderErrorCode.ORDER_ALREADY_PAID);
throw new RestApiException(OrderErrorCode.ORDER_NOT_FOUND, "주문 123을 찾을 수 없습니다.");
```

## 확인할 점

- `type` slug는 컨벤션상 `<모듈>/<에러 코드 이름>`(예: `order/member-not-found`)이다. `ErrorCode.slug()`의 기본 구현은 모듈 접두사 없이 이름만 반환하므로, 새 `*ErrorCode`는 `MemberErrorCode`처럼 `slug()`를 오버라이드해 모듈 접두사를 붙인다(`CommonErrorCode`는 접두사 없음). `OrderErrorCode`, `PayoutErrorCode`는 오버라이드가 없어 컨벤션과 다르다. 이 둘을 임의로 고치지 않고 사용자에게 알린다.
- 같은 모듈에 같은 이름의 코드가 이미 없는지 확인한다.
