# 개발 가이드

paldo-gotgan 프로젝트를 시작하고 실행하는 데 필요한 정보를 정리한 문서입니다. 개발을 시작하기 전에 이 문서를 먼저 읽으세요.

## 문서 안내

| 문서 | 내용 |
|---|---|
| [`README.md`](../README.md) | 저장소 첫 화면: 프로젝트 소개, 모듈 구조, 실행·빌드 명령 요약 (자세한 내용은 이 문서로 링크) |
| `DEVELOPMENT.md` (이 문서) | 프로젝트를 시작하고 실행하는 데 필요한 정보: 기술 스택, 사전 준비, 로컬 실행, 설정과 환경변수, 타임존, Docker, 시스템 아키텍처 |
| [`CONVENTION.md`](CONVENTION.md) | 기능을 구현할 때 지키는 기준: 이름, 구조, 패키지별 코드, 테스트 작성 기준, Git 규칙 |
| [`INFRA.md`](INFRA.md) | AWS 인프라를 Terraform으로 관리하는 방법: 실행 순서, state 관리, 다른 계정으로 옮기기 |
| [`DEPLOY.md`](DEPLOY.md) | EC2에 컨테이너로 배포하는 방법: 구성, 최초 설정, 배포, 롤백 |

같은 내용을 두 문서에 적으면 서로 어긋나므로, 한 내용은 **한 문서에만** 두고 다른 문서에서는 링크합니다.
기능을 구현하기 전에 `CONVENTION.md`도 읽어 주세요.

## 1. 기술 스택

| 구분 | 내용 |
|---|---|
| 언어 / 빌드 | Java 25, Gradle (Kotlin DSL) |
| 프레임워크 | Spring Boot 4.1.1 (Web MVC, Security, Validation) |
| 데이터 | MySQL 9.4, Spring Data JPA, Querydsl, Flyway |
| 구조 | Gradle 멀티 모듈 + Spring Modulith (도메인 모듈 분리) |
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
./gradlew :monolith:bootRun            # 모든 모듈을 함께 (포트 8080)
./gradlew :wallet:bootRun              # 또는 모듈 하나만 단독으로 (6절 참고)
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
- 호스트 포트는 `.env`의 `APP_PORT`(기본 8080)입니다. IntelliJ나 `:monolith:bootRun`으로 앱을 같이 실행 중이면 포트가 겹치니 하나만 실행하세요.
- 프로파일을 지정하지 않으면 `dev`로 실행됩니다.

## 4. 설정 구조

### 프로파일

| 프로파일 | 사용 시점 | 설정 파일 |
|---|---|---|
| `dev` | 기본값 (지정하지 않으면 dev) | `application.yaml` + `application-dev.yml` |
| `prod` | 운영. `SPRING_PROFILES_ACTIVE=prod` 로 지정 | `application.yaml` + `application-prod.yml` |
| `test` | 테스트. Gradle이 자동 지정 | `application.yaml` + `application-test.yml` |

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
| `APP_TIME_ZONE` | JDBC, Hibernate, Jackson 공통 타임존 | `Asia/Seoul` |
| `PROBLEM_BASE_URI` | 오류 응답 `type` 주소 접두사. `about:blank`이면 `type`을 `about:blank`로 둠 | `about:blank` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 앱의 DB 접속 정보 (직접 지정할 때) | 로컬 MySQL 기본값 |

### 새 설정값을 환경변수로 추가하는 방법

1. 설정 파일에 `${변수명:기본값}` 형식으로 작성 (예: `enabled: ${BATCH_JOB_ENABLED:false}`)
2. `.env.example`에 같은 이름과 설명을 추가
3. 이 문서의 표에 반영

> `.env` 값을 바꿔도 Gradle은 테스트를 다시 실행하지 않습니다(up-to-date). `./gradlew cleanTest test`로 실행하세요.

### 타임존

기본은 Asia/Seoul입니다. `.env`가 없어도 JDBC·Hibernate·Jackson이 Asia/Seoul을 쓰고, 서버(Dockerfile)·CI 테스트(`build-logic`의 `ok.java-conventions` 플러그인)의 JVM과 MySQL 컨테이너도 Asia/Seoul로 고정되어 있습니다.
`.env`에 `APP_TIME_ZONE=UTC`처럼 다른 값을 넣으면 기본값을 덮어쓰므로, 팀 기준과 다르게 두지 마세요.
`DATETIME` 컬럼은 타임존 없이 값만 저장하므로, 데이터가 쌓인 뒤에 바꾸면 기존 값이 어긋나 보일 수 있습니다.

### 모듈 설정 파일 (`application-<모듈>.yaml`)

모듈을 단독으로 실행하거나 모듈 테스트를 돌릴 때의 설정은 모듈마다 `src/main/resources/application-<모듈>.yaml` 하나에 모두 있습니다. `common`에는 설정 파일을 두지 않습니다. 서비스를 나눌 때 모듈 폴더만 떼어 가면 설정이 함께 따라가도록 한 구조이고, 그 대신 같은 값이 모듈마다 반복됩니다.

**`application.yaml`이 아니라 프로파일 파일을 쓰는 이유**

- `monolith`는 모든 모듈의 jar를 클래스패스에 올립니다. 모듈마다 `application.yaml`을 두면 어느 것이 읽힐지가 클래스패스 순서에 달려 `monolith`의 설정이 가려질 수 있습니다.
- 프로파일 파일은 모듈 앱 클래스(`<모듈>Application`)가 `common`의 `StandaloneApplication.run(...)`으로 `<모듈>` 프로파일을 켤 때만 읽히므로 `monolith`와 섞이지 않습니다. 모듈 테스트는 컨벤션 플러그인이 같은 프로파일을 `spring.profiles.include`로 켭니다. 통합 테스트의 `@ActiveProfiles("test")`가 `spring.profiles.active`를 덮어쓰기 때문에 `include`로 더합니다.

**`StandaloneApplication`**

- 모듈 앱 클래스의 `main`이 호출하는 공통 실행 도우미로, 모듈 이름 프로파일을 켭니다. 프로파일을 지정하지 않았으면 `monolith`의 기본 프로파일처럼 `dev`도 켭니다. 접속 DB와 포트는 이 코드가 아니라 모듈의 yaml이 갖습니다.
- Swagger UI(`/swagger-ui/index.html`)와 `/v3/api-docs`는 `dev`·`test` 프로파일일 때만 로그인 없이 열립니다(`SecurityConfig`). 모듈을 프로파일 없이 단독 실행하면 `dev`가 켜져 열리고, `SPRING_PROFILES_ACTIVE`나 `--spring.profiles.active`로 프로파일을 지정하면 그 값과 모듈 프로파일만 켜져 `dev`가 켜지지 않으므로 `401`로 막힙니다. 운영 같은 환경에서 문서가 열리지 않게 하려는 기준입니다.
- 모듈 간 연동을 메시지 브로커로 바꾸기 전까지는 모듈을 이 도우미로 단독 실행합니다. 그래서 `common`에 남겨 두며, 브로커 연동 뒤에 거취를 다시 정합니다.

**키별 설명**

| 키 | 값 | 설명 |
|---|---|---|
| `ok.flyway.standalone` | `true` | 접속 기본 DB가 모듈 자기 스키마이므로 `common` 마이그레이션(`event_publication`)도 그 스키마에 별도 이력 테이블(`flyway_schema_history_common`)로 적용합니다([`ModuleFlyway`](../common/src/main/java/com/ok/common/config/ModuleFlyway.java)). `monolith`에는 이 값이 없어 기본 스키마에 적용합니다 |
| `server.port` | `${SERVER_PORT:<포트>}` | 단독 실행 포트. 환경변수 `SERVER_PORT`로 바꿀 수 있고, 모듈마다 달라야 합니다 |
| `spring.application.name` | 모듈 이름 | 로그와 메트릭의 앱 이름 |
| `spring.config.import` | 루트 `.env`, 모듈 `.env` | 읽는 순서는 아래 [`.env` 읽는 순서](#env-읽는-순서-모듈-단독-실행) |
| `spring.datasource.url` | `jdbc:mysql://${DB_HOST}:${DB_PORT}/paldogotgan_<모듈>?...` | 이 모듈이 접속하는 DB. 스키마 이름이 URL에 적혀 있고 `<모듈>Schema.NAME`(엔티티의 `@Table(catalog)`)과 같아야 합니다. `monolith`용 `DB_URL`은 읽지 않습니다 |
| `spring.datasource.username`, `password` | `${DB_USERNAME:myuser}`, `${DB_PASSWORD:password}` | 모듈 `.env`의 모듈 계정. `.env`가 없으면 공용 계정으로 접속합니다 |
| `spring.jpa.hibernate.ddl-auto` | `${JPA_DDL_AUTO:validate}` | 테이블은 Flyway가 만들므로 기본은 검증만 합니다 |
| `spring.flyway.out-of-order` | `${FLYWAY_OUT_OF_ORDER:false}` | 순서가 뒤바뀐 마이그레이션 허용 여부. 기본은 운영과 같게 막고, 개발 중 필요할 때만 `FLYWAY_OUT_OF_ORDER=true`로 엽니다 |
| `spring.modulith.events.republish-outstanding-events-on-restart` | `${MODULITH_REPUBLISH_ON_RESTART:false}` | 재시작할 때 처리하지 못한 이벤트를 다시 발행할지 |
| `spring.jackson`, `spring.jpa.properties.hibernate.jdbc.time_zone` | `${APP_TIME_ZONE:Asia/Seoul}` | JSON, JDBC, Hibernate의 시간대. 자세한 내용은 [타임존](#타임존) |
| `jwt.verification-keys` | `file:${OK_ROOT_DIR:.}/keys/rs256-v1-public.pem` | 검증용 공개키. 저장소 루트의 `keys/`에서 읽습니다. member는 서명용 개인키 `jwt.signing`(`keys/rs256-v1-private.pem`)도 둡니다. 파일이 없으면 기동이 실패합니다(테스트는 `application-test.yml`의 테스트 키를 씁니다). 자세한 내용은 [키 관리와 교체](#키-관리와-교체) |
| `jwt.issuer`, `audience`, `*-expiration` | 고정값 | 토큰 발급·검증 기준. 모듈마다 같은 값이어야 서로의 토큰을 검증할 수 있습니다 |
| `app.problem.base-uri`, `app.s3.*` | `${PROBLEM_BASE_URI}`, `${AWS_REGION}`, `${S3_BUCKET}` | 오류 응답의 type 주소, S3 리전과 버킷 |
| `management.*`, `logging.level.com.ok` | health만 노출, `${LOG_LEVEL_OK:INFO}` | 헬스체크 노출 범위와 로그 레벨 |

위 공통 키 아래에는 그 모듈만 쓰는 설정이 이어집니다. `wallet`의 `toss.payments.*`, `delivery`의 `delivery.default-shipping.*`, `media`의 `spring.servlet.multipart.*`, `payout`의 `spring.batch.*`가 그 예입니다.

#### `.env` 읽는 순서 (모듈 단독 실행)

1. 루트 `.env`: `AWS_*`, `TOSS_SECRET_KEY`처럼 모듈이 함께 쓰는 값입니다. 모듈 `bootRun`이 환경변수 `OK_ROOT_DIR`로 저장소 루트를 알려 줍니다(`ok.spring-module-conventions`). Gradle 밖(IDE, jar 직접 실행)에서는 이 값이 없어 현재 디렉터리의 `.env`만 읽습니다.
2. 현재 디렉터리의 `.env`: `bootRun`의 작업 디렉터리가 모듈 폴더라서 `<모듈>/.env`, 곧 모듈의 DB 계정입니다. 나중에 읽은 값이 이기므로 모듈 값이 우선합니다.

OS 환경변수는 두 파일보다 우선합니다(예: `DB_PORT=3307 ./gradlew :member:bootRun`). 어느 파일도 없으면 각 키의 기본값이 쓰입니다.

#### 설정을 바꿀 때

- 모든 모듈이 같아야 하는 값(JWT 발급자, 로그 레벨 등)을 바꾸려면 모듈 파일을 모두 고칩니다. 반복은 분리를 위해 감수한 비용입니다.
- 한 모듈만 다른 값을 쓰는 것은 의도한 동작입니다.
- `monolith`는 자기 `application.yaml`을 쓰므로, 같은 값이라면 그쪽도 함께 확인합니다.

#### 테스트와 새 모듈

- 컨벤션 플러그인이 테스트에 `spring.profiles.include=<모듈>`과 `ok.schema=paldogotgan_<모듈>`을 지정합니다. `ok.schema`는 테스트 컨테이너(`TestcontainersConfiguration`)가 접속할 기본 DB를 고르는 데만 쓰고 앱 설정에는 쓰이지 않습니다.
- `./gradlew createModule`이 [`build-logic/templates/application-module.yaml`](../build-logic/templates/application-module.yaml)에서 `application-<모듈>.yaml`을 만듭니다. 포트는 기존 모듈이 쓰는 가장 큰 포트 다음 값을 자동으로 배정하고(`-Pport=<포트>`로 지정할 수 있고, 이미 쓰는 포트는 거부합니다), 모듈 이름·앱 클래스 이름·스키마 상수 이름만 자리표시자(`@MODULE@`, `@APP@`, `@APP_SCHEMA@`, `@PORT@`)로 바뀝니다. 공통 키를 새로 더하거나 바꿀 때는 템플릿과 기존 모듈 파일을 함께 고칩니다.

## 5. DB 마이그레이션 (Flyway)

스키마는 Flyway로 관리합니다. 마이그레이션 파일 작성 규칙(파일명, 수정 금지, 병합 순서 등)은 [`CONVENTION.md`의 DB 마이그레이션](CONVENTION.md#8-db-마이그레이션-flyway)을 참고하세요.

- 마이그레이션 파일 위치: 모듈 서브프로젝트의 `src/main/resources/db/migration/<모듈>/` (`common`의 `event_publication`은 `common/src/main/resources/db/migration/common/`)
- `ddl-auto`는 `validate`입니다. 엔티티를 추가하거나 수정하면 대응하는 마이그레이션이 반드시 필요하고, 없으면 앱이 기동하지 않습니다 (테스트에서도 같은 검증이 돕니다).
- 개발 환경(dev 프로파일)은 `flyway.out-of-order: true`로 순서가 뒤바뀐 마이그레이션도 적용합니다. 운영은 순서대로 적용됩니다.

### 모듈별 스키마

모듈은 자기 테이블을 자기 MySQL 스키마에 둡니다. 접속 DB(`DB_URL`)는 기본 스키마(`paldogotgan`) 하나이고, 엔티티가 `@Table(catalog = <모듈>Schema.NAME)`으로 자기 스키마를 지정합니다.

| 스키마 | 소유 |
|---|---|
| `paldogotgan` (기본) | common: `event_publication`, Flyway 이력 |
| `paldogotgan_member`, `_wallet`, `_order`, `_product`, `_delivery`, `_payout`, `_media`, `_refund` | 각 모듈의 테이블과 Flyway 이력 (payout은 Spring Batch의 `BATCH_*` 테이블도 가짐) |

- 스키마마다 Flyway가 따로 돕니다. 기본 스키마(common)가 먼저 실행되고, 모듈 스키마는 `createSchemas=false`라서 스키마가 없으면 새로 만들지 않고 실패합니다.
- 네이티브 SQL은 엔티티의 `catalog`가 적용되지 않으므로 테이블 이름을 스키마로 직접 한정합니다(`WalletSchema.NAME + ".buyer_wallet"`). JPQL, Spring Data, QueryDSL은 그대로 쓰면 됩니다.
- 새 데이터 볼륨으로 처음 뜨는 MySQL 컨테이너가 스키마를 만들고 앱 계정에 권한을 줍니다(`deploy/initdb`).

### 스키마·계정 생성

**스키마 생성 (`deploy/initdb`)**

- MySQL 컨테이너가 **빈 데이터 디렉터리로 처음 뜰 때만** `deploy/initdb/10-create-module-schemas.sh`가 실행되어 모듈 스키마를 만들고 앱 계정(`MYSQL_USER`)에 권한을 줍니다. 로컬(`infra/mysql/compose.yml`)과 운영(`deploy/compose.prod.yml`)이 같은 파일을 씁니다. 이미 데이터가 있는 DB에서는 돌지 않으므로 [로컬 DB를 이전하기](#로컬-db를-이전하기)나 [`DEPLOY.md`의 이전 절차](DEPLOY.md#8-모듈별-db-스키마-이전-288-한-번만)를 씁니다.
- 스키마 이름은 `paldogotgan_<모듈>`이고 각 모듈의 `<모듈>Schema.NAME`과 같아야 합니다. 스크립트는 모듈 이름을 소문자 영문으로 제한하고(SQL에 그대로 들어가기 때문입니다), `GRANT`의 DB 이름에서 `_`가 한 글자 와일드카드이므로 `\_`로 이스케이프합니다(안 하면 `paldogotganXmember` 같은 다른 DB에도 권한이 갑니다).
- 모듈 목록은 같은 폴더의 `modules.txt`에서 읽습니다. 이 파일은 `settings.gradle.kts`의 모듈에서 `./gradlew generateInitDb`가 만든 파일이라 직접 고치지 않습니다. 도메인 모듈은 `common`과 `monolith`를 뺀 서브프로젝트입니다.
- 모듈을 추가하거나 삭제했는데 `generateInitDb`를 실행하지 않으면 `./gradlew check`의 `verifyInitDb`가 실패합니다. 이미 만든 로컬 DB 볼륨에는 새 스키마가 없으므로 직접 만들거나 볼륨을 초기화합니다.
- 테스트 컨테이너(`TestcontainersConfiguration`)도 같은 `deploy/initdb` 폴더를 컨테이너에 넣어 운영과 같은 스크립트로 스키마를 만듭니다. 폴더 경로는 Gradle이 시스템 프로퍼티 `ok.initdb-dir`로 넘기므로, Gradle 없이 IDE에서 직접 실행하면 `-Dok.initdb-dir=<저장소>/deploy/initdb`를 지정합니다.

**모듈 DB 계정 (`infra/mysql/sync-module-users.sh`)**

모듈 단독 실행용 계정은 `<모듈>/.env`의 `DB_USERNAME`, `DB_PASSWORD`를 정본으로 하고, 스크립트가 같은 계정을 MySQL에 만들어 자기 스키마에만 권한을 줍니다.

| 명령 | 동작 |
|---|---|
| `./infra/mysql/sync-module-users.sh` | `<모듈>/.env`가 있는 모듈만 MySQL에 반영합니다 |
| `./infra/mysql/sync-module-users.sh --init` | `.env`가 없는 모듈은 `.env.example`에서 만들고(비밀번호는 무작위) 반영합니다 |

- 계정이 이미 있으면 비밀번호만 `ALTER USER`로 맞춥니다(`CREATE USER IF NOT EXISTS`는 기존 계정의 비밀번호를 바꾸지 않습니다). 여러 번 실행해도 결과가 같고, 이미 떠 있는 DB에도 쓸 수 있습니다.
- 값은 SQL에 그대로 들어가므로 형식을 제한합니다. 사용자 이름은 영문·숫자·`_` 1~32자이고 `root`나 공용 앱 계정(`MYSQL_USER`)은 쓸 수 없으며 모듈끼리 겹칠 수 없습니다. 공용 계정의 비밀번호를 바꾸면 `monolith`가 깨지기 때문입니다. 비밀번호는 영문·숫자와 `. _ ~ @ % + = -`만 쓴 12자 이상입니다.
- 비밀번호는 명령행 인자가 아니라 표준 입력으로 MySQL에 넘깁니다.
- 기본 대상은 이 저장소의 docker compose MySQL입니다. 다른 MySQL이면 `DB_EXEC`로 명령을 바꿉니다. root 비밀번호는 컨테이너의 `MYSQL_ROOT_PASSWORD`를 씁니다.

```bash
DB_EXEC="docker exec -i <컨테이너 이름>" ./infra/mysql/sync-module-users.sh
```

### Flyway 구성 (`ModuleFlyway`)

Boot의 Flyway 자동 설정은 Flyway 빈이 하나라는 전제라서, 스키마마다 Flyway를 직접 만듭니다. 직접 만든 빈에는 `spring.flyway.*` 속성이 자동으로 적용되지 않으므로 `out-of-order`, `validate-on-migrate`를 [`ModuleFlyway`](../common/src/main/java/com/ok/common/config/ModuleFlyway.java)가 읽어 넣습니다. 그래서 개발 환경(dev)의 `out-of-order=true`가 모든 인스턴스에 그대로 적용됩니다. 모듈 설정 클래스(`<모듈>FlywayConfig`)는 자기 스키마와 모듈 이름만 넘기고, `monolith`와 모듈 단독 앱이 같은 클래스를 씁니다. `ModuleFlyway`가 빈이 아니라 정적 도우미인 이유는 모듈 테스트(`@ApplicationModuleTest`)가 모듈 밖(`common`) 클래스의 빈을 걸러 내기 때문입니다.

실행 방식은 `ok.flyway.standalone` 하나로 갈립니다.

| | monolith | 모듈 단독 |
|---|---|---|
| 접속 기본 DB | 기본 스키마(`paldogotgan`) | 모듈 자기 스키마 |
| common 마이그레이션 | 기본 스키마, 이력 `flyway_schema_history` | 모듈 스키마, 별도 이력 `flyway_schema_history_common` |
| 모듈 마이그레이션 | 자기 스키마, 이력 `flyway_schema_history` | 같음 |

모듈 단독에서 common 이력을 따로 두는 이유는, 모듈 이력에 섞이면 같은 DB를 `monolith`로 띄울 때 모듈 인스턴스의 검증이 실패하기 때문입니다.

설계 이유

- `createSchemas=false`: 스키마가 없으면 새로 만들지 않고 실패합니다. 이전하지 않은 옛 구조 DB에 빈 모듈 테이블이 새로 생기는 사고를 막기 위해서이고, 스키마는 DB 준비 스크립트가 만듭니다.
- 기본 스키마 Flyway는 `ignoreMigrationPatterns`를 비웁니다. Flyway 기본값(`*:future`)은 "로컬에 없고 가장 최신 버전보다 새로운" 적용 이력을 무시하는데, 이전하지 않은 옛 구조 DB에는 모듈 마이그레이션 행이 그런 상태로 남아 있습니다. 무시하면 이전 안 된 DB에서 앱이 뜨는 사고를 막지 못하므로, 모르는 이력이 있으면 실패하게 합니다.
- 모듈 단독의 common Flyway는 버전 0으로 기준선을 잡고 시작합니다. 모듈 Flyway가 먼저 돌아 스키마가 비어 있지 않고 이 이력 테이블은 아직 없어서, 기준선이 없으면 Flyway가 "비어 있지 않은 스키마인데 이력이 없다"며 실패합니다. 그래서 단독 common 인스턴스는 모듈 initializer를 파라미터로 받아 모듈 마이그레이션이 끝난 뒤 실행되게 합니다.
- common initializer가 모듈 initializer보다 먼저 실행됩니다. 옛 구조 DB에서는 common 이력에 모듈 마이그레이션 행이 남아 있어 common 검증이 실패하는데, 이 실패가 빈 모듈 테이블 생성보다 앞서야 하기 때문입니다.
- `ok.flyway.single-history`는 모듈 테스트 전용입니다. 슬라이스 테스트(`@DataJpaTest`)는 모듈별 Flyway 설정을 읽지 않고 Boot의 Flyway 하나(모듈과 common 위치 전부)만 쓰는데, 같은 테스트 DB를 쓰는 전체 컨텍스트 테스트와 이력이 갈리면 검증이 실패합니다. 이 값이 켜지면 모듈 Flyway 하나가 common 마이그레이션까지 한 이력에 적용합니다. 앱 실행에서는 쓰지 않습니다.

### 로컬 DB를 이전하기

이 구조로 바뀌기 전에 만든 로컬 DB(`data/mysql`)를 그대로 두고 앱을 띄우면 Flyway 검증(`Detected applied migration not resolved locally`)에서 멈춥니다. 둘 중 하나로 맞춥니다.

- **방법 A: 데이터 유지.** 앱을 멈추고(`docker compose stop app`) 이전 스크립트를 실행합니다. Flyway 이력이 알려진 55건(전부 성공)과 정확히 같은 DB만 이전합니다. 점검이 실패하면(병합되지 않은 브랜치의 마이그레이션이 있거나, 로컬 DB가 일부만 적용돼 이력이 모자란 경우) 방법 B로 초기화합니다. root 비밀번호는 컨테이너의 환경변수(`MYSQL_ROOT_PASSWORD`)를 쓰므로 명령행에 쓰지 않습니다.
  ```bash
  MYSQL_EXEC='docker compose -f infra/mysql/compose.yml exec -T mysql' \
    infra/mysql/migration-288/migrate.sh all paldogotgan myuser
  ```
  결과(행 수·체크섬 비교)는 `infra/mysql/migration-288/out/<시각>/`에 남습니다. 이 폴더는 커밋하지 않습니다. 이전 후 이전 버전 코드(브랜치)로 돌아가려면 `rollback`을 먼저 실행하세요.
- **방법 B: 초기화.** `docker compose down` 후 `data/mysql`을 지우고 다시 `docker compose up -d`합니다. 새 볼륨이라 MySQL이 스키마를 만들고, 앱을 띄우면 Flyway가 테이블을 만듭니다.

## 6. 모듈 구조 (Gradle 서브프로젝트 + Spring Modulith)

도메인 모듈마다 Gradle 서브프로젝트이고, 모든 모듈을 함께 올리는 `monolith`가 운영 배포 단위입니다. `com.ok` 바로 아래 패키지가 하나의 모듈이고, 서브프로젝트 이름과 같습니다.

| 서브프로젝트 | 설명 | 단독 실행 포트 |
|---|---|---|
| `common` | 공통 코드와 전역 설정(`com.ok.common.config`의 `SecurityConfig`, JWT 검증, Flyway 도우미 등). 도메인을 참조하지 않는 코드만 둔다. 앱이 아니라 라이브러리 | - |
| `media`, `member`, `wallet`, `order`, `product`, `delivery`, `payout`, `refund` | 도메인 모듈. 각자 단독으로 기동하는 앱 클래스(`com.ok.<모듈 이름>Application`)를 가진다 | 8081, 8082, 8083, 8084, 8085, 8086, 8087, 8088 (이 순서) |
| `monolith` | 모든 모듈을 의존하는 통합 앱(`PaldoGotganApplication`). 운영 이미지가 이것이고, 모듈 간 이벤트 연동은 이 앱에서만 동작한다. 여러 모듈을 가로지르는 테스트를 둔다 | 8080 |

- 공통 빌드 설정은 `build-logic`(컨벤션 플러그인 `ok.java-conventions`, 도메인 모듈용 `ok.spring-module-conventions`)에, 의존 버전은 `gradle/libs.versions.toml`에 있습니다. 컨벤션 플러그인은 플러그인, 툴체인, 테스트 설정 같은 공통 설정만 주고, 의존성은 모듈의 `build.gradle.kts`에 직접 적습니다.
- 웹, 보안, JPA, Flyway, MySQL, Modulith 같은 스타터는 `common`이 `api`로 열어 둡니다. 도메인 모듈은 `implementation(project(":common"))`에 자기만 쓰는 의존성(예: `media`의 썸네일, `payout`의 배치)을 더합니다.
- 도메인 모듈끼리는 `project(...)` 의존을 걸지 않습니다. 다른 도메인 모듈은 `build.gradle.kts`에 `project(...)`로 선언하지 않으면 컴파일할 수 없고, 선언은 PR diff에서 바로 보이므로 리뷰에서 확인합니다. 모듈 간 연동은 `common.event`의 이벤트로 합니다.
- 모듈 안의 패키지 구조와 모듈 간 규칙(공개 위치, 순환 의존 금지 등)은 [`CONVENTION.md`의 패키지·모듈 구조](CONVENTION.md#2-패키지모듈-구조)를 참고하세요.

### 모듈 하나만 실행·테스트하기

```bash
./gradlew :wallet:bootRun        # wallet만 단독 실행 (포트 8083, 접속 DB는 paldogotgan_wallet)
./gradlew :wallet:test           # wallet 테스트만
./gradlew :wallet:build          # wallet 컴파일·테스트·jar
```

- 모듈의 설정은 그 모듈의 `application-<모듈>.yaml`이 모두 갖고 `common`에는 두지 않습니다. 키별 설명과 `.env` 읽는 순서는 [모듈 설정 파일](#모듈-설정-파일-application-모듈yaml)을 봅니다.
- 접속 DB는 모듈 자기 스키마(`paldogotgan_<모듈>`)입니다. 계정은 `<모듈>/.env`, 모듈이 함께 쓰는 값(`AWS_*` 등)은 루트 `.env`, JWT 키는 루트의 `keys/`에 두고, 모듈이 쓰는 값(wallet은 `TOSS_SECRET_KEY`)은 환경변수나 `.env`로 넘깁니다. `monolith`용 `DB_URL`은 읽지 않습니다.
- 모듈 계정은 자기 스키마에만 권한을 갖습니다(`member` 계정은 `paldogotgan_delivery`를 읽을 수 없습니다). `./infra/mysql/sync-module-users.sh --init`이 `<모듈>/.env.example`에서 `<모듈>/.env`를 만들고(비밀번호는 무작위) MySQL에 같은 계정을 만듭니다. 이미 떠 있는 DB에도 쓸 수 있고 여러 번 실행해도 됩니다. `<모듈>/.env`가 없으면 공용 계정(`myuser`)으로 접속하는 기존 방식이 그대로 동작합니다. `monolith`는 모듈 계정을 쓰지 않고 공용 계정(루트 `.env`의 `MYSQL_*`)으로 모든 스키마에 접속합니다.
- 단독 실행한 앱은 다른 모듈이 없어 모듈 간 이벤트 연동과 `/internal/**` 호출은 동작하지 않습니다. DB의 모듈 스키마는 [로컬 DB를 이전하기](#로컬-db를-이전하기)를 거치거나 새 볼륨이어야 만들어져 있습니다.
- 단독 실행한 모듈 스키마와 monolith는 같은 DB를 번갈아 써도 됩니다. 단독 실행은 `common` 마이그레이션(`event_publication`)을 같은 스키마의 별도 이력 테이블(`flyway_schema_history_common`)에 적용하므로 모듈 이력에는 섞이지 않습니다. 단독 실행이 common 행을 모듈 이력(`flyway_schema_history`)에 함께 기록하던 초기 개발 빌드로 만든 로컬 DB는 monolith가 `Detected applied migration not resolved locally: 202610081120`으로 멈춥니다. 모듈 스키마마다 `DELETE FROM paldogotgan_<모듈>.flyway_schema_history WHERE version = '202610081120';`로 그 행을 지우거나 볼륨을 초기화하세요(운영에는 해당 없음).
- 모듈의 테스트도 같은 프로파일로 돌고 접속 DB가 자기 모듈 스키마입니다(컨벤션 플러그인이 지정). 공용 테스트 지원(`AbstractIntegrationTest`, Testcontainers, 토큰 발급 등)은 `common`의 테스트 소스를 `testSupportElements` 구성으로 노출해 쓰며, 다른 모듈의 테스트 소스는 가져오지 않습니다. `java-test-fixtures`(jar) 대신 test 소스셋의 classes 디렉터리를 노출하는 이유는, Spring Modulith가 jar 안의 `com.ok.testsupport`를 도메인 모듈로 인식해 구조·모듈 테스트가 깨지기 때문입니다. 모듈에서는 `testImplementation(project(path = ":common", configuration = "testSupportElements"))`로 가져옵니다.

### 새 모듈 만들기

```bash
./gradlew createModule -PmoduleName=notification -PdisplayName=알림
```

`notification`/`알림`은 예시 값이고 실제로는 `-PmoduleName=<모듈이름>`(영문 소문자, 이미 있는 이름은 불가) `-PdisplayName=<표시이름>`을 씁니다. 모듈 폴더, `build.gradle.kts`, 기본 패키지(`package-info`), 앱 클래스, 설정(`application-<모듈>.yaml`), `.env.example`, `ArchitectureTest` 틀을 만듭니다. 설정은 `build-logic/templates/application-module.yaml`에서 만들고 포트는 기존 모듈 다음 값을 자동으로 배정합니다(`-Pport=<포트>`로 바꿀 수 있습니다). 이어서 직접 할 일은 실행 끝에 안내됩니다: `settings.gradle.kts`의 `include`에 추가, 운영에 포함하려면 `monolith/build.gradle.kts`에 `implementation(project(":모듈"))` 추가. 스키마를 쓰는 모듈이면 `<모듈>Schema`, `<모듈>FlywayConfig`를 만들고(`media`를 참고), `settings.gradle.kts`에 모듈을 추가한 뒤 `./gradlew generateInitDb`로 스키마 목록(`deploy/initdb/modules.txt`)을 갱신합니다. 이미 만든 로컬 DB 볼륨에는 새 스키마가 없으므로 직접 만들거나 볼륨을 초기화합니다. 목록을 갱신하지 않으면 `./gradlew check`가 실패합니다. 모듈 단독 실행용 DB 계정은 `./infra/mysql/sync-module-users.sh --init`으로 만듭니다.

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

### 토큰 형식

토큰은 RFC 7519(JWT) 형식이고, 등록 클레임 중 필요한 것만 씁니다. 권한은 비공개 클레임(`roles`)으로 담습니다.

| 위치 | 이름 | 값 | 용도 |
|---|---|---|---|
| 헤더 | `alg` | `RS256` | 서명 알고리즘 |
| 헤더 | `typ` | `at+jwt` | Access Token임을 표시. 이 타입만 받아 다른 용도의 JWT와 섞이지 않게 함 (RFC 9068, RFC 8725) |
| 헤더 | `kid` | `jwt.signing.key-id` 설정값 | 서명 키 식별자. 검증할 공개키를 고르고, 키를 교체할 때 어떤 키로 서명했는지 구분 |
| 클레임 | `iss` | `paldo-gotgan` | 우리가 발급한 토큰인지 검증 |
| 클레임 | `sub` | 회원 id | `@CurrentMemberId`가 읽는 값 |
| 클레임 | `aud` | `jwt.audience` 설정값 | 이 서비스를 위한 토큰인지 검증 |
| 클레임 | `jti` | UUID | 토큰 개별 폐기(로그아웃·탈취 대응)용 |
| 클레임 | `iat` | 발급 시각 | 추적, 특정 시점 이전 토큰 일괄 무효화용 |
| 클레임 | `exp` | 발급 시각 + 30분 | 만료 검증 (필수) |
| 클레임 | `roles` | 예: `["BUYER", "SELLER"]` | 역할 기반 인가 (각 값이 `ROLE_` 권한으로 변환) |

- `nbf`(사용 시작 시각)는 넣지 않습니다. 발급 즉시 유효한 토큰이라 `iat`와 같은 값이 되고, 서버 간 시계 차이로 정상 토큰이 거부될 위험만 생깁니다.
- 이름·이메일 같은 개인정보는 넣지 않습니다. JWT는 누구나 내용을 볼 수 있고, 회원 정보가 바뀌어도 토큰 만료 전까지 예전 값이 남습니다.
- `aud`는 지금 받는 서비스가 하나라 늘 같은 값이지만, 서비스가 분리되면 이미 발급된 토큰도 구분할 수 있도록 미리 넣습니다.
- `roles`는 회원에 저장된 값이 아니라 토큰을 발급할 때 회원 정보로 계산한 인가용 권한입니다. 권한 이름은 `TokenRoles`에 있습니다.


### 서명 방식

- **RS256(비대칭키)을 씁니다(#313).** 토큰을 발급하는 member(auth)만 개인키로 서명하고, 나머지 앱은 공개키로 검증만 합니다. 모듈마다 앱이 따로 떠서 검증하는 곳이 여럿이기 때문입니다. 대칭키(HS256)는 검증 키로 서명도 할 수 있어서, 검증만 하는 앱도 아무 회원·권한(ADMIN 포함)의 토큰을 만들 수 있었습니다.
- **알고리즘 고정**: 디코더는 RS256만 받습니다. 헤더의 `alg`대로 검증 방식을 고르면 공개키를 HMAC 비밀키로 써서 만든 HS256 토큰(알고리즘 혼동 공격)이 통과할 수 있습니다.
- **`kid`로 키 선택**: 디코더는 토큰 헤더의 `kid`에 해당하는 공개키(`jwt.verification-keys`)로 검증하고, 목록에 없는 `kid`는 거부합니다.
- 서명 알고리즘과 키는 `JwtConfig`(검증), `JwtEncoderConfig`(발급)에서만 관리하므로 각 모듈의 인가 코드는 알고리즘과 무관합니다.
- 지금은 공개키를 파일로 나눠 둡니다. member가 JWKS 엔드포인트로 공개키를 내주고 검증 쪽이 그것을 받아 쓰는 방식은 후속 작업에서 도입합니다. 그러면 키를 교체할 때 검증하는 앱을 하나하나 바꾸지 않아도 됩니다.

### 키 관리와 교체

**설정**

| 키 | 두는 앱 | 설명 |
|---|---|---|
| `jwt.verification-keys[].key-id`, `public-key` | 모든 앱 | 검증용 공개키 목록. `public-key`는 X.509 PEM 파일 위치(`file:`, `classpath:`) |
| `jwt.signing.key-id`, `private-key` | member, monolith | 서명용 개인키. `private-key`는 PKCS#8 PEM 파일 위치. `key-id`는 검증 목록에 있어야 하고, 개인키가 그 공개키와 짝이 아니면 기동하지 않습니다 |

- 로컬은 저장소 루트의 `keys/rs256-v1-public.pem`, `keys/rs256-v1-private.pem`을 씁니다(`keys/`는 `.gitignore`에 있음). 다른 위치를 쓰려면 `.env`에 `jwt.signing.private-key=file:/절대경로.pem`처럼 설정 키를 직접 적습니다.
- 운영(monolith, `prod` 프로필)은 서버 키 폴더의 `jwt.yaml`을 반드시 읽어 위 설정을 덮어씁니다(`application-prod.yml`). 이미지를 다시 만들지 않고 키를 교체하기 위해서입니다.
- 키 파일 이름은 `kid`를 따릅니다(`<kid>-private.pem`, `<kid>-public.pem`). 교체 중에는 옛 키와 새 키가 한 폴더에 함께 있기 때문입니다.
- RSA 키는 2048비트 이상이어야 기동됩니다. 개발·운영 환경은 서로 다른 키를 쓰고, 개인키는 저장소에 올리지 않습니다.
- 테스트는 `common/src/test/resources/jwt`의 테스트 전용 키 쌍을 씁니다(`application-test.yml`). 개발·운영에서 쓰지 않습니다.

**로컬 키 만들기** (저장소 루트에서, 처음 한 번)

```bash
mkdir -p keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/rs256-v1-private.pem
openssl pkey -in keys/rs256-v1-private.pem -pubout -out keys/rs256-v1-public.pem
```

운영 서버의 키는 [`DEPLOY.md`의 JWT 서명 키](DEPLOY.md#9-jwt-서명-키-313)를 봅니다.

**키 교체** (예: `rs256-v1` → `rs256-v2`)

`kid` 형식은 `{알고리즘}-v{버전}`이고, 토큰 헤더에 그대로 노출되므로 키 내용이나 추측 가능한 정보를 넣지 않습니다.
정기 교체는 하지 않습니다. 개인키 유출이 의심되거나 서버에 접근할 수 있는 사람이 바뀌었을 때 교체합니다. 운영 서버에서의 실제 명령은 [`DEPLOY.md`의 키 교체](DEPLOY.md#키-교체)를 봅니다.

1. 새 키 쌍(`rs256-v2-*.pem`)을 만들고, 검증하는 모든 앱의 `jwt.verification-keys`에 `rs256-v2` 공개키를 **추가**합니다. 서명은 아직 `rs256-v1`입니다.
2. 1이 모든 앱에 반영된 뒤, member·monolith의 `jwt.signing`을 `rs256-v2`와 새 개인키로 바꿉니다. 새 토큰은 `rs256-v2`로 서명되고, 이전 토큰도 `rs256-v1` 공개키로 계속 검증됩니다.
3. Access Token 유효 기간(30분)이 지나면 검증 목록에서 `rs256-v1`을 빼고 그 키 파일을 지웁니다.

- 1과 2를 한 번에 반영하면 새 토큰을 아직 `rs256-v2`를 모르는 앱이 거부할 수 있습니다. 앱이 하나(운영의 monolith)일 때는 `jwt.yaml`을 한 번 고쳐 함께 반영합니다.
- 개인키가 유출되었으면 3을 기다리지 않고 옛 키를 바로 뺍니다. 그 키로 서명된 토큰이 모두 거부되고, 사용자는 Refresh Token으로 재발급받습니다. Refresh Token은 JWT가 아니라서 영향이 없습니다.

### 토큰 재발급

Access Token(30분)이 만료되면 로그인 때 받은 Refresh Token으로 새 토큰을 받습니다.

1. API가 401 `token-expired`를 반환하면 `POST /api/v1/auth/reissue`에 `{"refreshToken": "..."}`를 보냅니다.
2. 성공하면 응답의 **새 Access Token과 새 Refresh Token으로 둘 다 교체**하고, 실패한 요청을 다시 보냅니다. 사용한 Refresh Token은 다시 쓸 수 없습니다.
3. 재발급이 401(`auth/invalid-refresh-token`)이면 다시 로그인합니다. 403(`auth/member-suspended`)은 정지된 회원입니다.
- 재발급할 때 회원 정보를 다시 읽어 최신 권한으로 발급합니다. 판매자 승인 같은 권한 변경도 재로그인 없이 반영됩니다.
- 같은 Refresh Token으로 동시에 요청하면 하나만 성공합니다. 여러 요청이 동시에 `token-expired`를 받아도 **재발급은 한 번만** 보내고, 나머지 요청은 그 결과를 기다렸다가 새 토큰으로 다시 보내야 합니다.
- 재발급 요청은 Access Token 없이 보냅니다. 만료된 토큰이 붙어 있어도 무시됩니다.
- 401의 `type`으로 다음 행동을 정합니다: `token-expired`는 재발급, `invalid-token`·`unauthorized`는 재로그인.

### 컨트롤러에서 로그인 회원 받기

요청 헤더(`X-Member-Id` 등)로 회원을 받지 말고 `@CurrentMemberId`를 씁니다. 값은 검증된 토큰에서 꺼내므로 클라이언트가 바꿀 수 없습니다.

```java
@GetMapping
public ResponseEntity<CartResponse> getMyCart(@CurrentMemberId Long memberId) {
    return ResponseEntity.ok(cartService.getMyCart(memberId));
}
```

`@CurrentMemberId`가 있는 API는 토큰 없이 호출하면 401이 됩니다. 별도 설정 없이 로그인이 필요한 API가 됩니다.

### 공개 API 추가하기

공개 API를 뺀 모든 API는 로그인이 필요합니다(기본 거부). 새 API는 따로 설정하지 않아도 토큰 없이 호출하면 401입니다.

로그인 없이 써야 하는 API(상품 조회처럼)를 만들면 `SecurityConfig`의 `PUBLIC_ENDPOINTS`에 HTTP 메서드, 경로, 토큰 처리 방식(`TokenUse`)을 추가합니다. 컨트롤러 경로와 문자열로 연결되어 있어, 경로를 바꾸면 여기도 함께 바꿉니다.

```java
new PublicEndpoint(HttpMethod.GET, "/api/v1/products/{productId}", TokenUse.IGNORED)
```

- 신원이 전혀 필요 없는 API는 `TokenUse.IGNORED`입니다. 붙어 온 토큰을 읽지 않아, 만료된 토큰이 있어도 401로 막히지 않습니다. 대신 `@CurrentMemberId`를 쓸 수 없습니다.
- 로그인 여부에 따라 응답이 달라지는 API(로그인했으면 찜 여부를 함께 보여 주는 식)는 `TokenUse.VERIFIED_IF_PRESENT`입니다. 토큰이 있으면 검증하므로 만료된 토큰이 붙으면 401입니다.
- 하위 경로가 함께 열리지 않도록 경로를 하나씩 적습니다. 예를 들어 리뷰 목록(`/products/{productId}/reviews`)은 공개지만 내 리뷰(`/products/{productId}/reviews/me`)는 로그인이 필요합니다.
- 내부 API(`/internal/**`)는 토큰 없이 호출하지만 같은 컨테이너(loopback)에서 온 요청만 받습니다. 외부 접근은 nginx도 막습니다.
- API 문서(Swagger)는 개발·테스트 프로필(`dev`, `test`)에서만 열립니다. 그 밖의 프로필(운영 등)에서는 로그인해도 막히고, 외부 접근은 nginx도 막습니다.

### 역할 제한

특정 역할만 쓸 수 있는 API는 컨트롤러 메서드에 `@PreAuthorize`를 붙입니다. 토큰의 권한 목록(`roles` claim)의 각 값이 `ROLE_{권한}` 권한으로 바뀌어 있습니다. 판매자 토큰에는 `BUYER`와 `SELLER`가 함께 들어 있어, 판매자도 구매 API를 쓸 수 있습니다.

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
 
// 여러 권한: tokens.bearer(1L, "BUYER", "SELLER")
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
- 공개 API(상품 조회 등)에 만료된 토큰을 붙이면 비로그인 화면도 401로 깨집니다. 프론트는 토큰 재발급이나 재로그인이 끝날 때까지 공개 API를 토큰 없이 호출하거나, 401을 받으면 토큰을 떼고 한 번 더 호출합니다.


### 최초 관리자 계정

관리자 생성 API는 관리자만 호출할 수 있어서, 첫 관리자는 앱이 시작될 때 `.env`의 값으로 만듭니다.

```properties
# .env (커밋하지 않음)
ADMIN_BOOTSTRAP_LOGIN_ID=admin01
ADMIN_BOOTSTRAP_PASSWORD=영문·숫자·특수문자를-포함한-8~20자
ADMIN_BOOTSTRAP_EMAIL=admin@example.com
```

- 앱을 시작하면 이 아이디의 관리자 계정이 생깁니다. 로그인하면 `ADMIN` 권한 토큰을 받습니다.
- 아이디를 비워 두면(기본) 만들지 않습니다. 아이디를 넣었다면 비밀번호·이메일도 넣어야 하고, 하나라도 빠지거나 형식이 틀리면 앱이 시작되지 않습니다.
- 아이디·비밀번호·이메일은 회원가입과 같은 규칙을 따릅니다 (아이디 영문 소문자·숫자 4~20자, 비밀번호 영문·숫자·특수문자 포함 8~20자).
- 같은 아이디의 회원이 이미 있으면 만들지 않고 **비밀번호도 바꾸지 않습니다.** 재시작할 때마다 덮어쓰면 바꾼 비밀번호가 설정값으로 되돌아가기 때문입니다. 그래서 `.env`의 비밀번호를 바꿔도 이미 만든 계정에는 반영되지 않습니다.
- 같은 아이디를 일반 회원이 쓰고 있으면 관리자를 만들지 않고 경고 로그를 남깁니다. 다른 아이디로 바꿔 주세요.
- 테스트 실행 중에는 동작하지 않습니다. 테스트도 `.env`를 읽지만, `application-test.yml`이 관리자 아이디를 비워 둡니다.
  
**비밀번호 관리**

- 로컬 DB는 각자 정해도 됩니다. 공유 개발 서버의 비밀번호만 팀이 같이 씁니다.
- 공유 서버의 비밀번호는 팀 비밀번호 관리 도구나 비공개 문서 한 곳에만 둡니다. Slack 채널, PR, 이슈에 평문으로 남기지 않습니다.
- 팀원이 빠지거나 유출이 의심되면 비밀번호를 바꿉니다. 위 이유로 `.env`만 바꿔서는 반영되지 않으니, 기존 계정을 지우고 다시 시작하거나 비밀번호 변경 기능을 씁니다.
- 공용 계정 하나를 모두가 쓰면 판매자 심사 기록(`reviewer_id`)이 모두 같은 사람이 됩니다. 운영 전에는 관리자 생성 API로 사람마다 관리자 계정을 만들고 공용 계정은 막습니다.

## 9. 테스트

### 테스트 환경 설정

- 테스트 리소스의 `application-test.yml`(`common/src/test/resources`, 모든 서브프로젝트의 테스트가 공유)에 테스트 전용 설정을 둡니다 (compose 비활성, 배치 Job 비활성, `ddl-auto: validate`).
- `build-logic`의 `ok.java-conventions` 플러그인이 `systemProperty("spring.profiles.active", "test")`를 지정하므로 Gradle로 실행하면 test 프로파일이 적용됩니다.
  IntelliJ에서는 테스트 실행 방식을 Gradle로 두세요.
- MySQL 버전은 `.env`의 `MYSQL_VERSION`을 따라가고, 없으면 `9.4`입니다. docker compose와 같은 값을 씁니다.
- MySQL 컨테이너는 서브프로젝트의 테스트 실행(JVM) 한 번에 하나만 뜨고 그 안의 모든 테스트 컨텍스트가 같은 DB를 씁니다. 컨테이너가 모듈 스키마를 모두 만들며, 모듈 테스트는 자기 모듈 스키마를, `monolith` 테스트는 기본 스키마를 접속 DB로 씁니다. 롤백되지 않는 테스트는 남긴 데이터를 직접 정리하세요.
- 테스트를 실행하려면 Docker가 켜져 있어야 합니다.

### 실행 명령

```bash
./gradlew test                                   # 전체 테스트(모든 서브프로젝트)
./gradlew :member:test                           # 서브프로젝트 하나만 (:monolith:test 등)
./gradlew :member:test --tests '*ArchitectureTest'  # 모듈의 구조 테스트만
./gradlew test --tests 'com.ok.SomeTest'         # 특정 클래스만
./gradlew test --tests 'com.ok.SomeTest.메서드'    # 특정 메서드만
./gradlew cleanTest test                         # 캐시 무시하고 다시 실행
```

커버리지 리포트는 테스트 실행 후 자동 생성됩니다.

- HTML: `<서브프로젝트>/build/reports/jacoco/test/html/index.html`
- XML: `<서브프로젝트>/build/reports/jacoco/test/jacocoTestReport.xml`

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
