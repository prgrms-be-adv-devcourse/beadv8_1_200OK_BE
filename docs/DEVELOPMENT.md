# 개발 가이드

paldo-gotgan 프로젝트의 기본 설정, 실행 방법, 개발 규칙을 정리한 문서입니다.

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

- 스키마 변경은 항상 `src/main/resources/db/migration`에 SQL 파일로 추가합니다.
- 파일명: `V{버전}__{설명}.sql` (예: `V2__create_member.sql`). 밑줄(`_`) 두 개에 주의하세요.
- **이미 적용된 마이그레이션은 수정하지 않습니다.** 체크섬이 달라져 앱이 기동하지 않습니다.
  변경이 필요하면 새 버전 파일을 추가하세요. 로컬에서만 쓴 파일이라면 `data/mysql`을 지우고 다시 적용해도 됩니다.
- `ddl-auto`는 `validate`입니다. 엔티티를 추가하거나 수정하면 대응하는 마이그레이션이 반드시 필요하고,
  없으면 앱이 기동하지 않습니다 (테스트에서도 같은 검증이 돕니다).
- `V1__create_event_publication.sql`은 Spring Modulith의 이벤트 발행 기록 테이블입니다. 지우지 마세요.

## 6. 모듈 구조 (Spring Modulith)

`com.ok` 바로 아래 패키지가 하나의 모듈입니다.

| 모듈 | 설명 |
|---|---|
| `member`, `wallet`, `order`, `product`, `delivery`, `refund`, `payout` | 도메인 모듈 |
| `common` | 공통 코드. 도메인을 참조하지 않는 코드만 둡니다 |
| `config` | 설정 클래스 (`SecurityConfig` 등). 도메인 모듈을 참조해도 됩니다 |

규칙

- 다른 모듈에 공개할 클래스(서비스, 이벤트)는 **모듈의 최상위 패키지**에 둡니다.
  하위 패키지(예: `order.internal`)는 다른 모듈에서 접근할 수 없습니다.
- 모듈 간 순환 의존은 금지입니다. `common`은 도메인 모듈을 import하지 않습니다.
- 모듈 간 연동은 가능하면 이벤트(`@ApplicationModuleListener`)로 합니다.
- 위반하면 `ModulithStructureTest`가 실패합니다. 새 모듈을 추가하면 이 테스트의 모듈 목록도 갱신하세요.
- 각 모듈 패키지의 `package-info.java`는 모듈 선언입니다. 지우지 마세요.

## 7. 테스트

### 종류와 위치

테스트는 `src/test/java`에서 main과 같은 패키지 구조로 둡니다. 이름은 한글로 동작을 설명합니다.

| 종류 | 사용 | 예 |
|---|---|---|
| 단위 테스트 | 순수 JUnit + AssertJ (Mockito). Spring 없이 | 도메인 로직, 서비스 로직 |
| 슬라이스 테스트 | `@DataJpaTest` + Testcontainers | 리포지토리, 쿼리, 마이그레이션 |
| **통합 테스트** | **`AbstractIntegrationTest`를 상속** | 여러 계층을 함께 검증하는 테스트 |
| 구조 테스트 | `ModulithStructureTest`, ArchUnit | 모듈 의존 규칙 |

작성 순서는 테스트 먼저(실패 확인) → 구현 → 정리입니다. 구조는 Arrange / Act / Assert로 나눕니다.

### 통합 테스트: `AbstractIntegrationTest` 상속

Spring 컨텍스트 전체와 MySQL 컨테이너가 필요한 테스트는 `com.ok.testsupport.AbstractIntegrationTest`를 상속합니다.

```java
class OrderServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Test
    void 주문을_생성하면_저장된다() {
        // Arrange
        // Act
        // Assert
    }
}
```

이 클래스가 대신 해 주는 것

- `@SpringBootTest`로 전체 컨텍스트 로드
- `@ActiveProfiles("test")`로 test 프로파일 적용
- `TestcontainersConfiguration`을 가져와 MySQL 컨테이너를 띄우고 데이터소스에 자동 연결 (`@ServiceConnection`)
- `@Tag("integrationTest")`로 통합 테스트 표시

직접 컨테이너를 만들거나 `@DynamicPropertySource`로 접속 정보를 넣을 필요가 없습니다.

### 슬라이스 테스트 (리포지토리)

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class MemberRepositoryTest {
    // @Autowired 로 리포지토리 주입
}
```

- import 경로 (Spring Boot 4):
  - `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`
  - `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase`
- 저장 여부를 확인하려면 `flush()`와 `clear()`로 영속성 컨텍스트를 비운 뒤 다시 조회하세요.

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

목표 커버리지는 80% 이상입니다. Querydsl이 생성하는 `Q*` 클래스는 현재 커버리지에서 제외하지 않았습니다.

## 8. Docker 이미지

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

## 9. Git 규칙

- `main`에 직접 커밋하지 않습니다. 이슈를 만든 뒤 브랜치를 만들어 작업하고 PR로 병합합니다.
- 브랜치: `feature/#<이슈번호>` (예: `feature/#42`)
- 커밋 메시지 형식

```
<Type>: <제목>

- 상세 내용

Ref: #<이슈번호>
```

- Type: `Feat`, `Fix`, `Docs`, `Style`, `Refactor`, `Test`, `Chore`, `Design`, `Comment`, `Rename`, `Remove`, `!BREAKING CHANGE`, `!HOTFIX`
- 커밋은 변경의 성격별로 나눕니다
- `.env`, 비밀번호, 토큰은 절대 커밋하지 않습니다. `.env.example`만 올립니다.
- 제목 첫 글자는 대문자로, 끝에는 `.` 금지
- 제목은 영문 기준 50자 이내로 할 것
- 여러가지 항목이 있다면 글머리 기호를 통해 가독성 높이기
- 이슈 번호는 본문 아래 꼬리말에 작성
