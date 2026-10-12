---
paths:
  - "src/main/java/**/service/**/*.java"
---

# 서비스·트랜잭션

서비스, 트랜잭션, 외부 호출, 로그를 작성할 때 따르는 규칙이다.
사람용 원문: `docs/CONVENTION.md` 2.3, 3.5, 7절.

## 트랜잭션 선언

- 서비스 클래스에 `@Transactional(readOnly = true)`를 선언하고, 데이터를 변경하는 메서드에만 `@Transactional`을 붙인다.
- 같은 클래스 안에서 `@Transactional` 메서드를 호출하지 않는다(프록시를 거치지 않아 무시된다).

**이유**: 기본값이 읽기 전용이면 조회 메서드에 따로 선언할 필요가 없고 실수로 쓰기가 섞이기 어렵다. 변경 메서드에만 `@Transactional`이 붙어 데이터가 바뀌는 지점이 눈에 띈다.

```java
// O
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletService {

    @Transactional
    public void createBuyerWallet(Long memberId) { ... }

    public Wallet getWallet(Long memberId) { ... }
}

// X: 변경 메서드에 @Transactional이 없다 (읽기 전용으로 실행된다)
public void createBuyerWallet(Long memberId) { ... }
```

## 외부 호출과 Facade

- 외부 시스템 호출(S3, 택배사 API 등)은 서비스가 아니라 `*Facade`에서 한다. Facade에는 `@Transactional`을 붙이지 않는다.
- DB 작업은 Facade가 서비스를 호출해서 한다. Facade는 `service` 패키지에 둔다.
- Facade가 서비스를 여러 번 호출하면 호출마다 별도 트랜잭션이다. 원자적으로 처리할 DB 작업은 서비스의 한 메서드에 둔다.
- 외부 시스템과 DB는 한 트랜잭션으로 묶을 수 없다. 한쪽이 성공한 뒤 다른 쪽이 실패하면 Facade에서 **보상 처리**로 성공한 쪽을 되돌린다. 보상 처리도 실패할 수 있으므로 실패하면 로그를 남긴다.

**이유**: 외부 호출이 느릴 때 트랜잭션과 DB 커넥션을 오래 잡지 않도록, 외부 호출은 트랜잭션 밖에서 하고 DB 저장만 트랜잭션에 넣는다. Facade가 서비스를 다른 빈으로 호출하므로 `@Transactional`도 정상 동작한다.

```java
// O
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
            fileStorage.delete(objectKey);                        // 보상 처리
            throw e;
        }
    }
}

// X: 트랜잭션이 걸린 서비스 안에서 외부 호출
@Transactional
public MediaInfo upload(MediaPurpose purpose, MultipartFile file) {
    s3Client.putObject(...);
    return mediaRepository.save(...);
}
```

## 현재 시각

- `Clock`을 주입받아 `LocalDateTime.now(clock)`로 얻는다. `LocalDateTime.now()`를 직접 호출하지 않는다.
- 테스트에서는 고정된 시각의 `Clock`을 사용한다.
- `Clock` 빈은 아직 없다. 별도 이슈에서 `APP_TIME_ZONE`을 따르도록 추가한다.

**이유**: 시각에 의존하는 로직을 현재 시각과 상관없이 같은 결과로 테스트할 수 있다. `LocalDateTime.now()`는 JVM 기본 타임존을 써서 앱 타임존 설정(`APP_TIME_ZONE`)과 어긋날 수 있다.

```java
// O
private final Clock clock;
LocalDateTime agreedAt = LocalDateTime.now(clock);

// 테스트
Clock clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);

// X
LocalDateTime agreedAt = LocalDateTime.now();
```

## 로그

- 메시지는 한국어로 쓰고, 식별자를 `key={}` 형태로 넣는다(`orderItemId={}`).
- 비밀번호, 토큰, 개인정보는 로그에 남기지 않는다.
- 예외를 던질 때 로그를 따로 남기지 않는다. 처리되지 않은 예외는 `ApiControllerAdvice`가 한 번만 남긴다.

**이유**: 문구와 레벨이 일정해야 로그를 검색하고 읽기 쉽다. 예외를 던지면서 로그도 남기면 같은 예외가 여러 번 기록되고, 개인정보가 남으면 유출 위험이 있다.

| 레벨 | 쓰는 경우 | 예 |
|---|---|---|
| `ERROR` | 처리되지 못한 예외(서버 오류) | `ApiControllerAdvice`의 처리되지 않은 예외 |
| `WARN` | 계속 진행하지만 확인이 필요한 이상 | 보상 처리 실패 |
| `INFO` | 업무상 의미 있는 사건 | 배치 종료, 중복 이벤트 무시 |
| `DEBUG` | 개발용 상세 | 중간 계산 값 |

```java
// O
log.info("이미 처리된 구매확정 이벤트, 무시. orderItemId={}", event.orderItemId());

// X
log.info("가입 처리 loginId=" + loginId + ", password=" + password);   // 문자열 결합, 비밀번호 노출

// X: 로그를 남기고 다시 던진다 (ApiControllerAdvice에서도 남겨 중복된다)
catch (RuntimeException e) {
    log.error("저장 실패", e);
    throw e;
}
```

## 서비스를 나누는 기준

- 도메인당 `*Service` 하나로 시작한다.
- 한 서비스의 책임이 서로 다른 이유로 변경되거나 의존하는 빈이 많아지면 유스케이스 단위 서비스로 나눈다.
- 처음부터 메서드 하나짜리 서비스를 유스케이스마다 만들지 않는다.

**이유**: 처음부터 잘게 나누면 클래스만 늘어난다. 나눌 이유는 코드가 커지면서 드러나므로 그때 나눈다.

```java
// O
class WalletService { ... }          // 시작
class MemberSignupService { ... }    // 책임이 갈라져 나눈 경우

// X
class WalletCreateService { ... }
class WalletChargeService { ... }
```

## DTO 위치

- `*Command`, `*Query`, `*Result`는 `service/dto`에 둔다.
- 서비스는 `*Response`를 반환하지 않는다. 식별자나 도메인 값을 반환하고 컨트롤러가 `*Response`를 만든다. 반환값이 여러 개면 `*Result`를 쓴다.

**이유**: 서비스(비즈니스)는 프레젠테이션 변화에 영향받지 않아야 하므로, 서비스가 컨트롤러의 타입(`*Response`)을 알면 안 된다.

```java
// X: 서비스가 응답 DTO를 반환한다
public MediaResponse upload(MediaPurpose purpose, MultipartFile file) { ... }
```

## 쓰기와 읽기 구분 (CQRS-lite)

- 상태를 바꾸는 유스케이스의 입력은 `*Command`, 조회 조건은 `*Query`로 이름 붙인다.
- 조회만 하는 저장소(Querydsl 등)는 `*QueryRepository`로 둔다.
- 읽고 쓰는 일이 섞인 유스케이스(정산 Job 등)는 쓰기로 보고 `*Command`로 취급한다.
- 서비스는 `*Service` 하나로 둔다. `*CommandService` / `*QueryService`로 나누지 않고, 읽기·쓰기 코드 모델도 분리하지 않는다.

**이유**: 읽기·쓰기 책임을 이름으로 구분하되 클래스 수를 늘리지 않는 가장 가벼운 방식이다. 서비스를 나누는 것은 서비스가 커졌을 때 다시 정한다.

```java
// X
record ProductSearchCommand(String keyword) { }   // 읽기 조건에 Command
class PayoutFindRepository { ... }                // 조회 전용인데 Query가 없다
```
