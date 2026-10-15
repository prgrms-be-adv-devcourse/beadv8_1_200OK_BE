# 개발 가이드

paldo-gotgan 프로젝트를 시작하고 실행하는 데 필요한 정보를 정리한 문서입니다. 개발을 시작하기 전에 이 문서를 먼저 읽으세요.

## 문서 안내

| 문서 | 내용 |
|---|---|
| `DEVELOPMENT.md` (이 문서) | 프로젝트를 시작하고 실행하는 데 필요한 정보: 기술 스택, 사전 준비, 로컬 실행, 설정과 환경변수, 타임존, Docker, 시스템 아키텍처 |
| [`CONVENTION.md`](CONVENTION.md) | 기능을 구현할 때 지키는 기준: 이름, 구조, 패키지별 코드, 테스트 작성 기준, Git 규칙 |

같은 내용을 두 문서에 적으면 서로 어긋나므로, 한 내용은 **한 문서에만** 두고 다른 문서에서는 링크합니다.
기능을 구현하기 전에 `CONVENTION.md`도 읽어 주세요.

## 1. 기술 스택

| 구분 | 내용 |
|---|---|
| 언어 / 빌드 | Java 25, Gradle (Kotlin DSL) |
| 프레임워크 | Spring Boot 4.1.1 (Web MVC, Security, Validation) |
| 데이터 | MySQL 9.4, Spring Data JPA, Querydsl, Flyway |
| 구조 | Spring Modulith (도메인 모듈 분리) |
| 배치 / 문서 / 외부 | Spring Batch, springdoc-openapi (Swagger), AWS SDK v2 (S3) |
| 테스트 | JUnit 5, AssertJ, Testcontainers, ArchUnit, JaCoCo |

## 2. 사전 준비

- **JDK 25** (Temurin 권장). jenv를 쓴다면 프로젝트 폴더에서 `jenv local temurin64-25.0.2`
  - Gradle은 JDK 21 이상에서 실행됩니다. `JAVA_HOME`이 17이면 빌드가 시작되지 않습니다.
  - `JAVA_HOME`이 바뀌지 않으면 `jenv enable-plugin export` 후 터미널을 다시 여세요.
- **Docker** (로컬 MySQL과 통합 테스트에 필요)
- 환경변수 파일 만들기

```bash
cp .env.example .env
```

`.env`는 git에 올라가지 않습니다(`.gitignore`). 값이 없으면 각 설정 파일의 기본값이 쓰입니다.

> 참고: docker compose 기본값(`infra/mysql/compose.yml`)과 앱 기본 접속 정보(`application.yaml`)는 같은 값
> (DB `paldogotgan`, 사용자 `myuser`, 비밀번호 `password`)이라 `.env` 없이도 실행됩니다.
> `.env`에서 `MYSQL_*` 값을 바꾸면 앱은 자동으로 따라가지 않으므로 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`도 같이 지정하세요.

## 3. 로컬 실행

```bash
# 1) DB 실행 (data/mysql 에 데이터 저장)
docker compose up -d

# 2) 앱 실행 (기본 프로파일 dev)
./gradlew bootRun
```

- `.env`에 `DOCKER_COMPOSE_ENABLED=true`를 넣으면 앱 실행 시 docker compose가 자동으로 켜지고, 앱을 종료하면 같이 꺼집니다.
  이때 DB(mysql)만 기동하고 앱 컨테이너는 만들지 않습니다.
- Swagger UI: `http://localhost:8080/swagger-ui/index.html` (dev에서만 열림)
- 현재는 로그인 없이 모든 요청을 허용합니다 (`config/SecurityConfig`). 인증을 구현하면 교체하세요.
- 로컬 DB를 처음 상태로 되돌리려면 앱과 DB를 끄고 `docker compose down` 후 `rm -rf data/mysql`
  (데이터가 모두 삭제됩니다).

### 앱까지 컨테이너(docker compose)로 실행

`docker compose up -d`는 DB만 띄웁니다. 앱을 컨테이너로 같이 실행하려면 `app` 프로파일을 지정합니다.

```bash
docker compose --profile app up -d --build   # 이미지 빌드 후 DB와 앱 실행
docker compose --profile app logs -f app     # 앱 로그
docker compose --profile app down            # 종료 (data/mysql 은 유지됨)
```

- DB가 healthy 상태가 된 뒤에 앱이 시작됩니다.
- 앱의 DB 접속 정보(`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`)는 `.env`의 `MYSQL_*` 값에서 자동으로 만들어져 DB 설정과 항상 같습니다.
- `.env`의 나머지 값(`BATCH_JOB_ENABLED`, `APP_TIME_ZONE` 등)도 컨테이너에 전달됩니다. `.env`가 없으면 기본값을 씁니다.
- 호스트 포트는 `.env`의 `APP_PORT`(기본 8080)입니다. IntelliJ나 `bootRun`으로 앱을 같이 실행 중이면 포트가 겹치니 하나만 실행하세요.
- 프로파일을 지정하지 않으면 `dev`로 실행됩니다.

## 4. 설정 구조

### 프로파일

| 프로파일 | 사용 시점 | 설정 파일 |
|---|---|---|
| `dev` | 기본값 (지정하지 않으면 dev) | `application.yaml` + `application-dev.yml` |
| `prod` | 운영. `SPRING_PROFILES_ACTIVE=prod` 로 지정 | `application.yaml` + `application-prod.yml` |
| `test` | 테스트. Gradle이 자동 지정 | `application.yaml` + `src/test/resources/application-test.yml` |

- 운영 배포 설정에는 `SPRING_PROFILES_ACTIVE=prod`를 반드시 넣으세요. 빠뜨리면 조용히 dev로 실행됩니다.
- 공통 설정은 `application.yaml`, 환경별 차이만 프로파일 파일에 둡니다.
- 비밀번호 같은 값은 공통 파일에 기본값을 두지 말고, 운영은 환경변수로만 받는 것을 원칙으로 합니다.

### 환경변수 (`.env`)

앱은 `.env`를 `spring.config.import`로 읽습니다. 프로젝트 루트에서 실행할 때만 읽힙니다.

| 변수 | 용도 | 기본값 |
|---|---|---|
| `MYSQL_VERSION` | MySQL 이미지 버전 (docker compose, 통합 테스트 공통) | `9.4` |
| `MYSQL_PORT` | 호스트에 열 DB 포트 | `3306` |
| `APP_PORT` | docker compose로 실행하는 앱 컨테이너의 호스트 포트 | `8080` |
| `MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` / `MYSQL_ROOT_PASSWORD` | docker compose의 MySQL 계정 | `.env.example` 참고 |
| `DOCKER_COMPOSE_ENABLED` | 앱 실행 시 compose 자동 기동 (dev) | `false` |
| `BATCH_JOB_ENABLED` | 앱 시작 시 배치 Job 자동 실행 (dev, 운영은 `true` 고정) | `false` |
| `MODULITH_REPUBLISH_ON_RESTART` | 재시작 시 미완료 이벤트 재발행 (dev, 운영은 `true` 고정) | `false` |
| `JPA_DDL_AUTO` | Hibernate `ddl-auto` (dev, 운영은 `validate` 고정) | `validate` |
| `APP_TIME_ZONE` | JDBC, Hibernate, Jackson 공통 타임존 | `UTC` |
| `PROBLEM_BASE_URI` | 오류 응답 `type` 주소 접두사. `about:blank`이면 `type`을 `about:blank`로 둠 | `about:blank` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 앱의 DB 접속 정보 (직접 지정할 때) | 로컬 MySQL 기본값 |

### 새 설정값을 환경변수로 추가하는 방법

1. 설정 파일에 `${변수명:기본값}` 형식으로 작성 (예: `enabled: ${BATCH_JOB_ENABLED:false}`)
2. `.env.example`에 같은 이름과 설명을 추가
3. 이 문서의 표에 반영

> `.env` 값을 바꿔도 Gradle은 테스트를 다시 실행하지 않습니다(up-to-date). `./gradlew cleanTest test`로 실행하세요.

### 타임존

기본은 UTC입니다. KST로 쓰려면 `.env`에 `APP_TIME_ZONE=Asia/Seoul`을 넣습니다.
`DATETIME` 컬럼은 타임존 없이 값만 저장하므로, 데이터가 쌓인 뒤에 바꾸면 기존 값이 어긋나 보일 수 있습니다.
데이터가 쌓이기 전에 정책을 정하세요.

## 5. DB 마이그레이션 (Flyway)

스키마는 Flyway로 관리합니다. 마이그레이션 파일 작성 규칙(파일명, 수정 금지, 병합 순서 등)은 [`CONVENTION.md`의 DB 마이그레이션](CONVENTION.md#8-db-마이그레이션-flyway)을 참고하세요.

- 마이그레이션 파일 위치: `src/main/resources/db/migration`
- `ddl-auto`는 `validate`입니다. 엔티티를 추가하거나 수정하면 대응하는 마이그레이션이 반드시 필요하고, 없으면 앱이 기동하지 않습니다 (테스트에서도 같은 검증이 돕니다).
- 개발 환경(dev 프로파일)은 `flyway.out-of-order: true`로 순서가 뒤바뀐 마이그레이션도 적용합니다. 운영은 순서대로 적용됩니다.

## 6. 모듈 구조 (Spring Modulith)

`com.ok` 바로 아래 패키지가 하나의 모듈입니다.

| 모듈 | 설명 |
|---|---|
| `member`, `wallet`, `order`, `product`, `delivery`, `refund`, `payout` | 도메인 모듈 |
| `common` | 공통 코드. 도메인을 참조하지 않는 코드만 둡니다 |
| `config` | 설정 클래스 (`SecurityConfig` 등). 도메인 모듈을 참조해도 됩니다 |

모듈 안의 패키지 구조와 모듈 간 규칙(공개 위치, 순환 의존 금지 등)은 [`CONVENTION.md`의 패키지·모듈 구조](CONVENTION.md#2-패키지모듈-구조)를 참고하세요.

## 7. 예외 처리 (ProblemDetail)

모든 오류 응답은 RFC 9457 `application/problem+json` 형식입니다. `common.exception.ApiControllerAdvice`가 예외를 변환합니다.

### 응답 필드

| 필드 | 설명 |
|---|---|
| `type` | 오류 유형을 식별하는 URI. 클라이언트는 이 값으로 분기합니다 |
| `title` | 유형별로 고정된 짧은 요약. 값을 끼워 넣지 않습니다 |
| `status` | HTTP 상태 코드 |
| `detail` | 이번 요청에 대한 구체적인 설명. 문구는 바뀔 수 있으니 파싱하거나 로직에 쓰지 않습니다 |
| `instance` | 오류가 발생한 요청 경로 (자동으로 채워짐) |
| `errors` | 입력값 검증 실패(422)일 때만. 필드별 오류 목록 |

```json
// 일반 오류 (409)
{
  "type": "https://docs.example.com/problems/order-already-paid",
  "title": "Order already paid",
  "status": 409,
  "detail": "주문 123은 이미 결제되었습니다.",
  "instance": "/api/v1/orders/123/pay"
}

// 입력값 검증 실패 (422)
{
  "type": "https://docs.example.com/problems/validation-failed",
  "title": "Validation failed",
  "status": 422,
  "detail": "요청 값이 올바르지 않습니다.",
  "instance": "/api/v1/members",
  "errors": [
    { "detail": "이름은 필수입니다", "pointer": "/name", "path": "name" },
    { "detail": "항목 이름은 필수입니다", "pointer": "/items/0/name", "path": "items[0].name" }
  ]
}
```

- `errors`의 `pointer`는 요청 본문 안의 위치(JSON Pointer, 기계용), `path`는 사용자에게 보여줄 경로입니다.
- 쿼리/경로 파라미터 오류는 필드 오류가 아니므로 `errors` 없이 `invalid-query-parameter`(400)로 응답합니다.

### 공통 오류 유형 (`CommonErrorCode`)

| 슬러그 | 상태 | 발생 상황 |
|---|---|---|
| `validation-failed` | 422 | `@Valid` 검증 실패 (`errors` 포함) |
| `unauthorized` | 401 | 토큰 없음 → 로그인 (`WWW-Authenticate: Bearer`) |
| `token-expired` | 401 | 토큰 만료 → 토큰 재발급 (`WWW-Authenticate: Bearer error="invalid_token", error_description=...`) |
| `invalid-token` | 401 | 위조·형식 오류 토큰 → 토큰을 버리고 재로그인 (`WWW-Authenticate: Bearer error="invalid_token"`) |
| `forbidden` | 403 | 로그인했지만 역할이 맞지 않음 (`@PreAuthorize` 등) |
| `invalid-json` | 400 | 요청 본문을 읽을 수 없음 (깨진 JSON 등) |
| `invalid-query-parameter` | 400 | 파라미터 타입 불일치 |
| `bad-request` | 400 | 그 밖의 잘못된 요청 (필수 파라미터 누락 등) |
| `internal-error` | 500 | 처리되지 않은 예외 |
404, 405, 406, 415 같은 그 밖의 Spring MVC 표준 예외는 Spring 기본 `ProblemDetail`로 응답합니다. 이 경우 `type`은 `about:blank`, `title`은 상태 코드 이름이고
`PROBLEM_BASE_URI`는 적용되지 않습니다. 구분이 필요해지면 `CommonErrorCode`에 유형을 추가하세요.

### type 주소 (`PROBLEM_BASE_URI`)

`type`은 `PROBLEM_BASE_URI`(접두사) + 슬러그입니다. 값은 `.env`에서 바꿉니다.

| `PROBLEM_BASE_URI` | `type` | `title` |
|---|---|---|
| `about:blank` (기본값) | `about:blank` | 상태 코드 이름 (`Not Found`) |
| `https://docs.example.com/problems/` | `https://docs.example.com/problems/validation-failed` | 유형별 제목 (`Validation failed`) |

- 오류 설명 페이지가 생기면 그 주소를 `.env`에 넣으면 됩니다. 접두사 끝의 `/`는 없어도 자동으로 붙습니다.
- 공통 오류의 슬러그는 에러 코드 enum 이름을 소문자와 하이픈으로 바꾼 값입니다 (`VALIDATION_FAILED` → `validation-failed`).
  모듈 에러 코드는 앞에 모듈 이름을 붙입니다 (`order/member-not-found`). 규칙은 [`CONVENTION.md`의 에러 응답 `type` 주소](CONVENTION.md#63-에러-응답의-type-주소slug)를 참고하세요.
  **배포한 뒤에는 `type`을 바꾸지 않습니다.** enum 이름을 바꿔야 하면 `slug()`를 재정의해 기존 값을 유지하세요.

### 참고

- 처리되지 않은 예외(500)는 내부 메시지와 스택 트레이스를 응답에 담지 않고 서버 로그에만 남깁니다.
- Spring Security 필터에서 발생하는 인증·인가 예외도 `ProblemDetailAuthenticationEntryPoint`, `ProblemDetailAccessDeniedHandler`가 이 핸들러로 넘겨 같은 형식으로 응답합니다.
- 모듈에서 에러 코드를 만들고 예외를 던지는 방법은 [`CONVENTION.md`의 예외 처리](CONVENTION.md#6-예외-처리)를 참고하세요.

## 8. 인증 (JWT)

로그인(`POST /api/v1/auth/login`)으로 받은 Access Token을 `Authorization: Bearer {토큰}` 헤더로 보냅니다.
보안 필터가 요청마다 토큰의 서명·만료·발급자를 검증하고, 실패하면 401로 응답합니다.

### 컨트롤러에서 로그인 회원 받기

요청 헤더(`X-Member-Id` 등)로 회원을 받지 말고 `@CurrentMemberId`를 씁니다. 값은 검증된 토큰에서 꺼내므로 클라이언트가 바꿀 수 없습니다.

```java
@GetMapping
public ResponseEntity<CartResponse> getMyCart(@CurrentMemberId Long memberId) {
    return ResponseEntity.ok(cartService.getMyCart(memberId));
}
```

`@CurrentMemberId`가 있는 API는 토큰 없이 호출하면 401이 됩니다. 별도 설정 없이 로그인이 필요한 API가 됩니다.

### 역할 제한

특정 역할만 쓸 수 있는 API는 컨트롤러 메서드에 `@PreAuthorize`를 붙입니다. 토큰의 역할(`role` claim)이 `ROLE_{역할}` 권한으로 바뀌어 있습니다.

```java
@PreAuthorize("hasRole('SELLER')")
@PostMapping
public ResponseEntity<ProductCreateResponse> create(@CurrentMemberId Long memberId, ...) { ... }
```

역할이 맞지 않으면 403, 토큰이 없으면 401입니다. 역할 규칙은 중앙 보안 설정이 아니라 각 모듈의 컨트롤러에 둡니다.

### 테스트에서 토큰 보내기

**통합 테스트** (`AbstractIntegrationTest` 상속): `TestAccessTokens`로 토큰을 만들고, MockMvc에 보안 필터를 적용합니다.

```java
@Autowired
TestAccessTokens tokens;

@BeforeEach
void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
}

mockMvc.perform(get("/api/v1/cart")
        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(1L, "BUYER")));
```

**컨트롤러 슬라이스 테스트** (`@WebMvcTest`): 실제 토큰 없이 `jwt()`로 로그인한 회원을 흉내 냅니다.
`@PreAuthorize`를 확인하려면 보안 필터를 끄지 말고(`addFilters = false` 제거) `SecurityConfig`를 함께 불러옵니다.

```java
mockMvc.perform(post("/api/v1/products")
        .with(jwt().jwt(token -> token.subject("1"))
                .authorities(new SimpleGrantedAuthority("ROLE_SELLER"))));
```

### Swagger, Bruno

- Swagger UI: 로그인 API로 받은 `accessToken`을 우측 상단 **Authorize**에 넣으면 모든 요청에 Bearer 헤더가 붙습니다.
- Bruno: `Auth/로그인` 요청을 보내면 토큰이 `accessToken` 변수에 저장되고, 컬렉션 전체 요청에 Bearer 헤더로 붙습니다.

### 주의

- 토큰이 있으면 공개 API여도 검증합니다. 만료되거나 잘못된 토큰을 보내면 공개 API도 401입니다. 401을 받으면 토큰을 지우고 다시 로그인하세요.
- 역할은 토큰에 담기므로, 판매자 승인처럼 역할이 바뀌어도 기존 토큰이 만료되기 전(최대 30분)까지는 이전 역할로 판단합니다.

## 9. 테스트

### 테스트 환경 설정

- `src/test/resources/application-test.yml`에 테스트 전용 설정을 둡니다 (compose 비활성, 배치 Job 비활성, `ddl-auto: validate`).
- `build.gradle.kts`의 `systemProperty("spring.profiles.active", "test")` 덕분에 Gradle로 실행하면 test 프로파일이 적용됩니다.
  IntelliJ에서는 테스트 실행 방식을 Gradle로 두세요.
- MySQL 버전은 `.env`의 `MYSQL_VERSION`을 따라가고, 없으면 `9.4`입니다. docker compose와 같은 값을 씁니다.
- 테스트를 실행하려면 Docker가 켜져 있어야 합니다.

### 실행 명령

```bash
./gradlew test                                   # 전체 테스트
./gradlew test --tests 'com.ok.SomeTest'         # 특정 클래스만
./gradlew test --tests 'com.ok.SomeTest.메서드'    # 특정 메서드만
./gradlew cleanTest test                         # 캐시 무시하고 다시 실행
```

커버리지 리포트는 테스트 실행 후 자동 생성됩니다.

- HTML: `build/reports/jacoco/test/html/index.html`
- XML: `build/reports/jacoco/test/jacocoTestReport.xml`

## 10. Docker 이미지

`Dockerfile`은 멀티 스테이지입니다 (빌드: JDK 25, 실행: JRE 25 Alpine, 비루트 사용자). 테스트는 이미지 빌드에서 제외됩니다.

```bash
# 이미지 만들기 (이름:태그)
docker build -t paldo-gotgan:0.0.1 .

# 여러 태그 / 레지스트리 이름
docker build -t paldo-gotgan:0.0.1 -t paldo-gotgan:latest .
docker build -t myname/paldo-gotgan:0.0.1 .

# 이미 만든 이미지에 태그 추가, 업로드
docker tag paldo-gotgan:0.0.1 myname/paldo-gotgan:0.0.1
docker push myname/paldo-gotgan:0.0.1

# 실행 (운영)
docker run -d --name paldo-gotgan -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_URL=jdbc:mysql://<호스트>:3306/<DB명> -e DB_USERNAME=... -e DB_PASSWORD=... \
  paldo-gotgan:0.0.1
```

- 태그는 `0.0.1` 같은 버전이나 커밋 해시(`$(git rev-parse --short HEAD)`)를 쓰고, 운영 배포에 `latest`는 피합니다.
- 태그에는 영문, 숫자, `_`, `.`, `-`만 쓸 수 있고 저장소 이름은 소문자여야 합니다.
- 이미지 안에는 `.env`가 들어가지 않습니다(`.dockerignore`). 접속 정보는 실행할 때 환경변수로 넘기세요.
- 컨테이너에서 DB가 같은 compose 네트워크(`db-net`)에 있다면 호스트 이름은 `mysql`입니다.
