---
paths:
  - "src/test/**/*.java"
---

# 테스트 작성 스타일

테스트 코드를 작성할 때 따르는 규칙이다. 테스트 종류 선택, 클래스 이름, 외부 시스템 경계는 `testing-strategy.md`를 본다.
사람용 원문: `docs/CONVENTION.md` 9절.

## 작성 순서

- 구현 후 테스트를 작성하고, 실행해서 통과를 확인한다. 실패하면 구현이나 테스트를 고쳐 다시 확인한다.
- 검증할 동작이 없는 테스트는 쓰지 않는다.

**이유**: TDD 정석(테스트 먼저)이 아니라, 편의를 위해 구현 후 테스트를 작성하는 순서를 따른다.

## 메서드 이름

- 한글로 동작을 설명한다. `@DisplayName`은 쓰지 않는다.

**이유**: 한글 메서드 이름이 이미 동작을 설명하므로 `@DisplayName`은 중복이다. 이름 하나만 보면 되게 한다.

```java
// O
void 지갑이_이미_있으면_저장하지_않는다() { ... }

// X: 이름과 설명이 중복되고 영어 이름은 동작을 알기 어렵다
@DisplayName("지갑이 이미 있으면 저장하지 않는다")
void createBuyerWallet_alreadyExists() { ... }
```

## 본문 구역

- `// given`, `// when`, `// then` 주석으로 나눈다. 소문자로 쓴다.
- 예외 검증은 `// when & then` 하나로 합친다.
- `// Given`, `// Arrange` / `// Act` / `// Assert` 표기는 쓰지 않는다.

**이유**: 준비, 실행, 검증이 한눈에 구분되어야 읽기 쉽다. 예외 검증은 실행과 검증이 `assertThatThrownBy` 한 문장에 함께 들어가므로 나누면 오히려 장황하다.

```java
// O: 정상 케이스
// given
Long memberId = 1L;

// when
Wallet sut = Wallet.create(memberId, WalletType.BUYER);

// then
assertThat(sut.getBalance()).isZero();

// O: 예외 케이스
// given
Long memberId = null;

// when & then
assertThatThrownBy(() -> Wallet.create(memberId, WalletType.BUYER))
        .isInstanceOf(IllegalArgumentException.class);
```

## 검증 대상 변수명 `sut`

- 검증 대상 객체의 변수명만 `sut`로 쓴다. 다른 변수는 평소처럼 짓는다.
- 검증 대상 변수가 없는 테스트(예외 검증 등)는 억지로 만들지 않는다.

**이유**: 준비물과 검증 대상이 섞여 있을 때 무엇을 검증하는지 바로 보이게 한다.

```java
// O
Wallet sut = Wallet.create(memberId, WalletType.BUYER);

// X
Wallet result = Wallet.create(memberId, WalletType.BUYER);
```

## 파라미터화 테스트

- 입력값은 `@MethodSource`로 제공한다. `@ValueSource`, `@NullAndEmptySource`는 쓰지 않는다.
- `null`, 빈 문자열, 공백도 같은 메서드 소스에 담는다.
- `@MethodSource` 메서드(`static`)는 그 값을 쓰는 테스트 바로 아래에 둔다. 여러 테스트가 공유하면 처음 쓰는 테스트 바로 아래에 둔다.
- 케이스가 1개뿐이면 `@Test`로 쓴다.

**이유**: 입력값이 테스트 바로 옆에 있어야 읽기 쉽고, 어노테이션을 나눠 쓰면 입력 목록이 여러 곳에 흩어진다. 케이스 하나에 파라미터화는 과하다.

```java
// O
@ParameterizedTest
@MethodSource("허용되지_않는_이미지_타입")
void 허용되지_않는_이미지_타입은_거부한다(String contentType) { ... }

static Stream<String> 허용되지_않는_이미지_타입() {
    return Stream.of("image/gif", "video/mp4", "", " ", null);
}

// X
@ParameterizedTest
@ValueSource(strings = {"image/gif", "video/mp4"})
@NullAndEmptySource
```

## 여러 필드 검증

- 한 객체의 필드를 여러 개 검증하면 `assertSoftly`를 쓴다.
- `assertSoftly` 바로 앞에서 `assertThat(객체).isNotNull()`을 먼저 한다.
- 단언이 하나뿐이면 일반 `assertThat`을 쓴다.

**이유**: 일반 `assertThat`은 첫 실패에서 멈춰 틀린 필드를 하나씩만 알려 준다. `assertSoftly`는 한 번에 보여 줘 원인 파악이 빠르고, 객체가 null이면 NPE가 나므로 먼저 거른다.

```java
// O
assertThat(sut).isNotNull();
assertSoftly(softly -> {
    softly.assertThat(sut.getMemberId()).isEqualTo(memberId);
    softly.assertThat(sut.getType()).isEqualTo(WalletType.BUYER);
});

// X: 첫 실패에서 멈춘다
assertThat(sut.getMemberId()).isEqualTo(memberId);
assertThat(sut.getType()).isEqualTo(WalletType.BUYER);
```

## 예외 검증

- AssertJ `assertThatThrownBy`로 통일한다. `assertThrows`, `catchThrowable`은 쓰지 않는다.
- `RestApiException`은 `isInstanceOfSatisfying`으로 에러 코드를 확인한다. 메시지 문자열이나 `extracting("errorCode")`는 쓰지 않는다.
- `RestApiException`이 아닌 예외(`IllegalArgumentException` 등)는 `hasMessage`로 메시지를 확인한다.

**이유**: 검증 방식이 하나여야 읽기 쉽고, 호출과 검증이 한 문장에 있어 `// when & then`과 맞는다. `e.getErrorCode()`는 이름이 바뀌면 컴파일 단계에서 알지만 필드 이름 문자열은 실행해야 깨진다. `detail` 문구는 바뀔 수 있어 에러 코드로 확인한다.

```java
// O
// when & then
assertThatThrownBy(() -> CartItem.create(cart, 1L, 1L, 0))
        .isInstanceOfSatisfying(RestApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_QUANTITY));

// X
.isInstanceOf(RestApiException.class)
.extracting("errorCode").isEqualTo(OrderErrorCode.INVALID_QUANTITY);
.hasMessage("수량은 1개 이상이어야 합니다.");
```
