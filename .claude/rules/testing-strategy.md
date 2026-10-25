---
paths:
  - "**/src/test/**/*.java"
---

# 테스트 종류와 경계

어떤 테스트를 쓸지, 클래스를 어떻게 이름 붙일지, 외부 시스템을 어떻게 다룰지 정한 규칙이다. 메서드 이름, 구역 주석, 단언 스타일은 `testing.md`를 본다.
사람용 원문: `docs/CONVENTION.md` 9.4, 9.9~9.12.

## 테스트 종류

정상 흐름은 통합 테스트에서 한 번 검증하고, 서비스 단위 테스트는 분기·실패·협력 동작 중심으로 쓴다.

| 종류 | 검증하는 것 | 방식 |
|---|---|---|
| 도메인 단위 | 엔티티, 값 객체, 정책 등 Spring 없이 돌릴 수 있는 순수 로직 | 순수 JUnit + AssertJ |
| 서비스 단위 | 분기, 예외, 이벤트 발행, 외부 경계 호출, 실패 경로 | Mockito |
| 통합 | Controller → Service → Repository 전체 흐름, 영속성·쿼리·제약·트랜잭션 | `AbstractIntegrationTest` 상속 |
| 모듈 | 한 모듈 안의 이벤트 연동 (이벤트 계약이 있는 모듈마다 둔다) | `@ApplicationModuleTest` |
| 구조 | 모듈 간 의존(Modulith), 모듈 안 레이어 의존 방향과 클래스 이름·위치(ArchUnit) | `ModulithStructureTest`, `ArchitectureTest` |

- Controller는 통합 테스트로만 검증한다. `@WebMvcTest`, `@DataJpaTest`는 쓰지 않는다.
- 통합 테스트는 `AbstractIntegrationTest`를 상속한다. `@SpringBootTest(properties=...)`나 `@Import`로 직접 설정하지 않는다(컨텍스트가 새로 떠서 느려진다).

**이유**: 통합 테스트가 레이어와 DB를 모두 검증하므로 저장소·컨트롤러를 따로 테스트하면 같은 것을 다시 검증한다. 서비스의 분기·실패 경로는 통합 시나리오로 다 덮기엔 많아 단위 테스트가 맡는다. 스프링 컨텍스트는 설정이 조금만 달라도 새로 떠서(8~10초) 느리고, `AbstractIntegrationTest`는 컨텍스트 하나를 공유한다.

```java
// O
class OrderServiceIntegrationTest extends AbstractIntegrationTest { ... }

// X
@SpringBootTest(properties = "...")
@Import(TestcontainersConfiguration.class)
class OrderServiceIntegrationTest { ... }
```

## 테스트 클래스

- 서브프로젝트의 `src/test/java`에서 테스트 대상과 같은 패키지에 둔다.
- package-private(`class`)로 선언한다. `public`을 붙이지 않는다.
- 이름 접미사는 종류별로 정한다.
  - 도메인·서비스 단위: `*Test`
  - 통합: `*IntegrationTest`
  - 모듈: `*ModuleTest`

**이유**: JUnit 5는 `public`을 요구하지 않아 붙이면 노출만 늘고, 접미사로 테스트 종류를 이름만 보고 안다.

```java
// O
class WalletTest { ... }
class MemberSignupIntegrationTest extends AbstractIntegrationTest { ... }

@ApplicationModuleTest
class WalletModuleTest { ... }

// X
public class WalletTest { ... }
class MemberSignupTest extends AbstractIntegrationTest { ... }   // 통합인데 *Test
```

## 서비스 단위 테스트

- 결과를 검증한다: 반환값, 저장한 엔티티 상태, 발행한 이벤트, 던진 예외. 저장한 값은 `ArgumentCaptor`로 확인한다.
- 호출 횟수·순서(`verify(times)`)는 그 자체가 요구사항일 때만 검증한다(예: "이미 있으면 저장하지 않는다").
- 저장소 쿼리 결과를 지나치게 세세하게 stub하지 않는다.

**이유**: 협력 객체를 Mockito로 대체한 테스트는 구현에 묶이기 쉽다. 결과 중심으로 검증하면 구현을 바꿔도 동작이 같으면 테스트가 깨지지 않는다.

```java
// O
ArgumentCaptor<Wallet> captor = ArgumentCaptor.forClass(Wallet.class);
then(walletRepository).should().save(captor.capture());
assertThat(captor.getValue().getType()).isEqualTo(WalletType.BUYER);

// X
verify(walletRepository, times(1)).existsByMemberIdAndType(1L, WalletType.BUYER);
verify(walletRepository, times(1)).save(any(Wallet.class));
```

## DTO 테스트

- 로직이 없는 DTO(필드만 가진 `record`)는 테스트하지 않는다.
- 로직이 있는 DTO(정규화, `toCommand` 같은 변환, `toString` 마스킹)는 순수 단위 테스트로 검증한다.
- 같은 타입 필드가 여러 개인 변환은 필드마다 다른 값을 넣고 모두 `assertSoftly`로 검증한다(순서 뒤바뀜을 잡기 위해서다).
- `*Result` → `*Response` 매핑은 따로 테스트하지 않고, 통합 테스트에서 응답의 모든 필드를 확인한다.

**이유**: `record`는 필드를 추가하면 생성하는 모든 곳이 컴파일 오류가 나므로 필드 누락은 컴파일러가 막는다. 컴파일러가 못 잡는 것은 같은 타입 필드의 순서 뒤바뀜이고, 변환 테스트는 이를 잡기 위해 쓴다.

```java
// X: 로직 없는 record의 생성자와 접근자를 검증한다
SignupResponse sut = new SignupResponse(1L);
assertThat(sut.memberId()).isEqualTo(1L);
```

## 외부 시스템 경계

- 외부 시스템(S3, 택배사 API 등)은 우리가 정의한 인터페이스 뒤에 둔다. 인터페이스는 외부 SDK 모양이 아니라 도메인 동작("파일을 저장한다", "배송을 요청한다")으로 정의한다.
- 통합 테스트에서는 외부를 호출하지 않고 `testsupport`의 Fake 구현으로 대체한다. Fake는 `AbstractIntegrationTest`에서 한 번만 등록해 모든 통합 테스트가 같은 컨텍스트를 공유하게 한다.
- 서비스 단위 테스트에서는 외부 인터페이스를 Mockito로 대체해도 된다.
- Fake는 호출 방식이 아니라 결과(저장된 상태)를 검증한다.

**이유**: Fake는 호출 방식이 아니라 결과를 검증하게 해서 외부 SDK 호출 방식이 바뀌어도 동작이 같으면 테스트가 깨지지 않는다. `@MockitoBean`은 대체 구성이 클래스마다 다르면 스프링 컨텍스트가 따로 떠서 느려진다.

```java
// O
assertThat(fakeFileStorage.contains(objectKey)).isTrue();

// X: 외부 SDK 호출 방식에 묶인다
verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
```
