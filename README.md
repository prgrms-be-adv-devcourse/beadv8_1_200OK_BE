# paldo-gotgan

Spring Boot 4 / Java 25 기반 백엔드입니다. 도메인별 Gradle 서브프로젝트(모듈)로 나뉘어 있고, 운영에서는 모든 모듈을 하나로 묶은 통합 앱(`monolith`)을 배포합니다. 모듈마다 자기 MySQL 스키마를 쓰며, 개발 중에는 모듈 하나만 단독으로 띄울 수도 있습니다.

| 구분 | 내용 |
|---|---|
| 언어 / 빌드 | Java 25, Gradle 멀티 모듈 (Kotlin DSL) |
| 프레임워크 | Spring Boot 4, Spring Modulith, Spring Batch |
| 데이터 | MySQL 9.4, Spring Data JPA, Querydsl, Flyway |

기술 스택의 자세한 내용은 [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)에 있습니다.

## 사전 준비

- JDK 25, Docker(로컬 MySQL과 통합 테스트에 필요)
- 환경변수 파일: `cp .env.example .env` 후 **`JWT_SECRET`을 `openssl rand -base64 32` 결과로 바꿉니다.** 나머지는 기본값으로 실행됩니다. 값의 의미는 `docs/DEVELOPMENT.md`의 환경변수 표를 봅니다.

## 모듈 구조

| 모듈 | 역할 |
|---|---|
| `common` | 공통 코드(보안·JWT 검증, 예외 응답, Flyway 도우미, 모듈 간 이벤트). 도메인을 참조하지 않는 라이브러리 |
| `member`, `wallet`, `order`, `product`, `delivery`, `payout`, `media`, `refund` | 도메인 모듈. 각자 단독 실행할 수 있는 앱 클래스와 자기 스키마(`paldogotgan_<모듈>`)를 가진다 |
| `monolith` | 모든 모듈을 묶은 통합 앱. 운영 배포 단위이고 모듈 간 이벤트 연동은 이 앱에서만 동작한다 |
| `build-logic` | 모든 모듈이 쓰는 빌드 설정(컨벤션 플러그인) |

의존 방향은 도메인 모듈 → `common`뿐입니다. 도메인 모듈끼리는 `project(...)` 의존을 걸지 않고 `common`의 이벤트로 연동합니다. 자세한 규칙은 [`docs/CONVENTION.md`](docs/CONVENTION.md)를 봅니다.

## 실행

### 1. 로컬 MySQL

```bash
docker compose up -d     # data/mysql 에 데이터 저장, 호스트 포트는 .env의 MYSQL_PORT(기본 3306)
```

데이터 디렉터리가 비어 있는 채로 처음 뜰 때 `deploy/initdb`가 모듈별 스키마 8개를 만들고 앱 계정(`myuser`)에 권한을 줍니다. 테이블은 앱이 뜰 때 Flyway가 만듭니다.

### 2. 통합 앱

```bash
./gradlew :monolith:bootRun
```

- 포트 8080, 기본 프로파일 `dev`. 헬스 체크 `http://localhost:8080/actuator/health`, Swagger UI `http://localhost:8080/swagger-ui/index.html` (dev에서만 열림)
- `MYSQL_PORT`를 바꿨다면 `DB_URL`도 같이 지정합니다(`docs/DEVELOPMENT.md` 참고).

### 3. 모듈 하나만 단독 실행

```bash
set -a; source .env; set +a     # JWT_SECRET 등을 셸 환경변수로 내보낸다 (단독 실행은 .env의 이 값을 직접 읽지 못한다)
./gradlew :wallet:bootRun       # wallet만 실행 (포트 8083, 접속 DB는 paldogotgan_wallet)
```

| 모듈 | media | member | wallet | order | product | delivery | payout | refund |
|---|---|---|---|---|---|---|---|---|
| 포트 | 8081 | 8082 | 8083 | 8084 | 8085 | 8086 | 8087 | 8088 |

- 단독 실행은 `standalone`과 모듈 이름 프로파일이 자동으로 켜집니다. DB 접속은 `DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD`로 지정합니다(`DB_URL`은 읽지 않습니다).
- **다른 모듈이 같이 떠 있지 않으므로 모듈 간 이벤트 연동(예: 회원 가입 → 지갑 생성)은 동작하지 않습니다.** 이 연동은 `monolith`에서만 됩니다.
- 헬스 체크는 `http://localhost:<포트>/actuator/health`입니다. 단독 실행에서는 Swagger UI가 인증을 요구합니다.

### 이미 쓰던 로컬 DB가 있는 경우

모듈별 스키마로 바뀌어 기존 `data/mysql` 볼륨은 그대로 쓸 수 없고, 앱을 띄우면 Flyway 검증에서 멈춥니다. 둘 중 하나로 맞춥니다.

- **방법 A, 데이터 유지**: 앱을 멈추고 `infra/mysql/migration-288/migrate.sh all paldogotgan myuser`로 이전합니다. Flyway 이력이 알려진 55건(전부 성공)인 DB만 이전합니다.
- **방법 B, 초기화**: `docker compose down` 후 `data/mysql`을 지우고 다시 `docker compose up -d`합니다.

명령 전체와 주의점은 [`docs/DEVELOPMENT.md`의 로컬 DB를 이전하기](docs/DEVELOPMENT.md#로컬-db를-이전하기)에 있습니다.

## 빌드와 테스트

```bash
./gradlew build                 # 전체 컴파일·테스트·jar (테스트 약 1975건, 이 저장소에서 약 3~4분)
./gradlew :wallet:test          # 모듈 하나의 테스트 (wallet은 255건, 약 35초)
./gradlew :wallet:build         # 모듈 하나의 컴파일·테스트·jar
```

- 통합 테스트는 Testcontainers로 MySQL을 띄우므로 Docker가 실행 중이어야 합니다.
- 테스트 종류와 작성 기준은 [`docs/CONVENTION.md`](docs/CONVENTION.md)의 테스트 절을 봅니다.

## 새 모듈 만들기

```bash
./gradlew createModule -PmoduleName=notification -PdisplayName=알림
```

`notification`/`알림`은 예시 값이고 실제로는 `-PmoduleName=<모듈이름>`(영문 소문자, 이미 있는 이름은 불가) `-PdisplayName=<표시이름>`을 씁니다. 모듈 폴더, `build.gradle.kts`, 기본 패키지, 앱 클래스, `ArchitectureTest` 틀을 만듭니다. 만든 뒤 직접 할 일은 작업 출력에 안내됩니다.

1. `settings.gradle.kts`의 `include(...)`에 모듈 이름 추가
2. 운영에 함께 올리려면 `monolith/build.gradle.kts`에 `implementation(project(":모듈"))` 추가

스키마를 쓰는 모듈이면 추가로 할 일이 있습니다. [`docs/DEVELOPMENT.md`의 새 모듈 만들기](docs/DEVELOPMENT.md#새-모듈-만들기)를 봅니다.

## Docker 이미지

```bash
docker build .                  # monolith 이미지 빌드 (이 저장소에서 약 2분)
docker compose --profile app up -d --build   # DB와 앱을 컨테이너로 함께 실행
```

EC2 배포(`deploy/`)와 롤백은 [`docs/DEPLOY.md`](docs/DEPLOY.md)를 봅니다.

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) | 기술 스택, 로컬 실행, 설정과 환경변수, 모듈별 스키마와 DB 이전, 모듈 구조 |
| [`docs/CONVENTION.md`](docs/CONVENTION.md) | 이름, 패키지 구조, 패키지별 코드, 테스트, Git 규칙 |
| [`docs/DEPLOY.md`](docs/DEPLOY.md) | EC2 컨테이너 배포, 롤백, 모듈별 스키마 이전(운영) |
| [`docs/INFRA.md`](docs/INFRA.md) | AWS 인프라(Terraform) |
