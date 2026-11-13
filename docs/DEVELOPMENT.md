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
| 데이터 | MySQL 9.4, Spring Data JPA, Querydsl, Flyway, Redis 8.10 (단일 또는 클러스터, Spring Data Redis, Lettuce) |
| 메시지 브로커 | Apache Kafka 4.2 (KRaft, 브로커 1대 또는 3대), Spring for Apache Kafka |
| 구조 | Gradle 멀티 모듈 + Spring Modulith (도메인 모듈 분리) |
| 배치 / 문서 / 외부 | Spring Batch, springdoc-openapi (Swagger), AWS SDK v2 (S3) |
| 테스트 | JUnit 5, AssertJ, Testcontainers, ArchUnit, JaCoCo |

## 2. 사전 준비

- **JDK 25** (Temurin 권장). jenv를 쓴다면 프로젝트 폴더에서 `jenv local temurin64-25.0.2`
  - Gradle은 JDK 21 이상에서 실행됩니다. `JAVA_HOME`이 17이면 빌드가 시작되지 않습니다.
  - `JAVA_HOME`이 바뀌지 않으면 `jenv enable-plugin export` 후 터미널을 다시 여세요.
- **Docker** (로컬 MySQL·Redis·Kafka와 통합 테스트에 필요). Kafka 브로커 3대가 약 1~2GB를 쓰므로 Docker에 줄 메모리를 여유 있게 둡니다.
- 환경변수 파일 만들기

```bash
cp .env.example .env
```

`.env`는 git에 올라가지 않습니다(`.gitignore`). 값이 없으면 각 설정 파일의 기본값이 쓰입니다.

> 참고: docker compose 기본값(`infra/mysql/compose.yml`)과 앱 기본 접속 정보(`application.yaml`)는 같은 값
> (DB `paldogotgan`, 사용자 `myuser`, 비밀번호 `password`)이라 `.env` 없이도 실행됩니다.
> `.env`에서 `MYSQL_*` 값을 바꾸면 앱은 자동으로 따라가지 않으므로 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`도 같이 지정하세요.

## 3. 로컬 실행

처음이면 위에서부터 순서대로 따라 하면 됩니다. 명령은 모두 저장소 루트에서 실행합니다.

### 한눈에 보기

`docker compose`가 띄우는 것은 **프로파일**로 나뉩니다. 프로파일이 없는 서비스는 항상 뜨고, 프로파일이 있는 서비스는 그 프로파일을 켰을 때만 뜹니다.

| 서비스 | 프로파일 | 호스트 포트 | 비고 |
|---|---|---|---|
| `mysql` | 없음(항상) | `3306` | 데이터는 `data/mysql` |
| `redis` (1대) | `single` | `6379` (`127.0.0.1`만) | AOF, 메모리 한도 512m, 비밀번호 없음. 평소 쓰는 구성 |
| `redis-1`~`redis-6` (6대), `redis-cluster-init` | `cluster` | `7001`~`7006` (`127.0.0.1`만) | 마스터 3 + 복제본 3. `single`과 함께 켜지 않는다 |
| `kafka` (브로커 1대) | `single` | `19092` (`127.0.0.1`만) | 메모리 한도 1024m. 평소 쓰는 구성 |
| `kafka-1`~`kafka-3` (브로커 3대) | `cluster` | `19092`, `29092`, `39092` | `single`과 함께 켜지 않는다 |
| `prometheus`, `grafana`, `mysqld-exporter`, `cadvisor`, `kafka-exporter` | `monitoring` | `9090`, `3000` (`127.0.0.1`만) | 모니터링 |
| `app` | `app` | `8080` (`APP_PORT`) | 앱까지 컨테이너로 실행 |

**프로파일을 켜는 방법은 두 가지이고, 섞어 쓰면 안 됩니다.**

1. `.env`의 `COMPOSE_PROFILES`에 쉼표로 적는다(권장). 예: `COMPOSE_PROFILES=single,monitoring`. 그러면 `docker compose up -d`만 하면 됩니다.
2. 명령줄에 `--profile`을 붙인다. 이때 **`.env`의 `COMPOSE_PROFILES`는 무시됩니다.** `.env`에 `single`이 있어도 `--profile monitoring`만 주면 Kafka는 빠집니다.

| 명령 | 뜨는 Kafka |
|---|---|
| `.env`에 `COMPOSE_PROFILES=single` 두고 `docker compose up -d` | `kafka`, `redis` |
| `.env`에 `COMPOSE_PROFILES=single,monitoring` 두고 `docker compose up -d` | `kafka` + 모니터링 |
| `docker compose --profile single --profile monitoring up -d` | `kafka` + 모니터링 |
| `docker compose --profile monitoring up -d` | **없음.** `kafka-exporter`만 떠서 브로커에 붙지 못한다 |

### 처음 한 번만 준비

1. Docker를 켭니다. Compose는 2.20 이상이어야 합니다(`docker compose version`). `depends_on`의 `required`를 쓰기 때문입니다.
2. `cp .env.example .env`를 실행합니다. 이 파일에 `COMPOSE_PROFILES=single`이 들어 있어 Kafka(브로커 1대)까지 뜹니다. `.env`가 이미 있으면 이 줄이 있는지 확인합니다.
3. 앱을 실행할 때는 JWT 키(`keys/`)가 필요합니다. 만드는 방법은 [8절 키 관리와 교체](#키-관리와-교체)의 "로컬 키 만들기"를 봅니다.

### 상황별 실행

| 하고 싶은 것 | 방법 | 뜨는 것 |
|---|---|---|
| 평소 개발(앱은 IDE나 `bootRun`) | `docker compose up -d` 후 `./gradlew :monolith:bootRun` | mysql, redis, kafka |
| 모니터링도 같이 | `.env`에 `COMPOSE_PROFILES=single,monitoring`을 적고 `docker compose up -d` | 위 + prometheus, grafana 등 |
| 앱까지 컨테이너로 | `docker compose --profile single --profile app up -d --build` (JWT 키 필요) | 위 + app |
| Kafka 브로커 3대, Redis 6대 | `.env`에 `COMPOSE_PROFILES=cluster`와 `REDIS_MODE=cluster`. 컨테이너 앱이면 `KAFKA_INTERNAL_BOOTSTRAP_SERVERS=kafka-1:9092,kafka-2:9092,kafka-3:9092`도 | kafka-1~3, redis-1~6 |
| 앱이 compose를 대신 켜게 | `.env`에 `DOCKER_COMPOSE_ENABLED=true`를 적고 `bootRun` | mysql, redis, kafka |

모니터링과 앱 컨테이너의 자세한 사용법은 아래 "로컬 모니터링", "앱까지 컨테이너(docker compose)로 실행"을 봅니다.

### 앱 실행

```bash
./gradlew :monolith:bootRun            # 모든 모듈을 함께 (포트 8080)
./gradlew :wallet:bootRun              # 또는 모듈 하나만 단독으로 (6절 참고)
```

- `.env`에 `DOCKER_COMPOSE_ENABLED=true`를 넣으면 앱 실행 시 docker compose가 자동으로 켜지고, 앱을 종료하면 같이 꺼집니다.
  이때 DB(mysql), Redis, Kafka(`.env`의 `COMPOSE_PROFILES`)만 기동하고 앱 컨테이너는 만들지 않습니다.
- Swagger UI: `http://localhost:8080/swagger-ui/index.html` (dev에서만 열림)
- 현재는 로그인 없이 모든 요청을 허용합니다 (`config/SecurityConfig`). 인증을 구현하면 교체하세요.

### 잘 떴는지 확인

```bash
docker compose ps                                           # mysql, redis, kafka가 모두 healthy인지
docker compose exec redis redis-cli ping                    # PONG
docker compose exec redis redis-cli config get appendonly   # yes (AOF가 켜짐)
docker compose exec kafka sh -c 'KAFKA_HEAP_OPTS=-Xmx64m /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list'   # 오류 없이 끝나면 브로커가 응답한다
# 메모리 한도와 swap 한도가 같으면 swap을 쓰지 않는다 (Redis 536870912, Kafka 1073741824)
docker inspect -f '{{.Name}} {{.HostConfig.Memory}} {{.HostConfig.MemorySwap}}' server-redis-1 server-kafka-1
```

- 컨테이너 이름은 프로젝트 이름(`server`)을 따라 `server-<서비스>-1`입니다. `cluster`의 첫 브로커는 `server-kafka-1-1`입니다.
- 앱 설정으로 실제 Redis·Kafka에 붙는지는 `./gradlew :common:infraTest`로 확인합니다(아래 "인프라 연결 확인 테스트").

### 멈추기와 초기화

| 하고 싶은 것 | 명령 | 데이터 |
|---|---|---|
| 잠시 멈춤 | `docker compose stop` (다시 켜려면 `start`) | 그대로 |
| 컨테이너 삭제 | `docker compose down` | 그대로(MySQL은 `data/mysql`, Kafka·Redis는 도커 볼륨) |
| Kafka·Redis·모니터링 데이터까지 삭제 | `docker compose down -v` | 도커 볼륨이 지워진다(토픽·메시지·AOF·지표). MySQL의 `data/mysql`은 그대로 |
| MySQL까지 처음 상태로 | `docker compose down` 후 `rm -rf data/mysql` | 모두 삭제 |

`stop`, `start`, `down`은 프로파일을 주지 않아도 이 프로젝트의 컨테이너 전체에 적용됩니다(프로파일이 있는 서비스 포함). 반대로 `up -d`는 켠 프로파일의 서비스만 올리고, 켜지 않은 프로파일의 컨테이너가 이미 떠 있어도 멈추지 않습니다. 그래서 `cluster`에서 `single`로 바꿀 때는 먼저 `docker compose down`으로 내립니다.

### 문제가 생기면

| 증상 | 원인 | 해결 |
|---|---|---|
| `docker compose ps`에 `kafka`가 없다. 앱이 Kafka에 붙지 못한다 | Kafka 프로파일이 켜지지 않았다. `.env`에 `COMPOSE_PROFILES`가 없거나, `--profile`을 줘서 `.env` 값이 무시됐다 | `.env`에 `COMPOSE_PROFILES=single`을 넣거나 명령에 `--profile single`을 붙인다 |
| `port is already allocated`(`6379`, `19092`, `3306`) | 다른 프로젝트의 컨테이너가 같은 포트를 쓴다. `single`과 `cluster`를 함께 켜도 `19092`가 겹친다 | 다른 쪽을 내리거나 `.env`의 `REDIS_PORT`, `KAFKA_1_PORT`, `MYSQL_PORT`를 바꾼다. Kafka 포트를 바꾸면 `KAFKA_BOOTSTRAP_SERVERS`도 같이 바꾼다 |
| Redis나 Kafka가 갑자기 종료됐다(`Exited (137)`) | 메모리 한도를 넘어 OOM kill됐다. swap을 쓰지 않아 한도에서 바로 죽는다 | `docker inspect -f '{{.State.OOMKilled}}' server-redis-1`이 `true`면 한도 초과다 |
| `single`↔`cluster`를 바꾼 뒤 컨테이너 앱이 브로커를 못 찾는다 | 앱이 `kafka:9092`(`single`의 기본 주소)로 붙는데 `cluster`에는 없다 | `cluster`면 `.env`에 `KAFKA_INTERNAL_BOOTSTRAP_SERVERS=kafka-1:9092,kafka-2:9092,kafka-3:9092`를 넣는다 |
| Kafka가 뜨지 않고 다시 시작을 반복한다 | `CLUSTER_ID`나 `KAFKA_VERSION`을 바꿔 기존 볼륨과 맞지 않는다 | `docker compose down -v`로 볼륨까지 지우고 다시 시작한다(메시지가 사라진다) |
| compose가 `required`를 모르는 속성이라고 한다 | Compose가 2.20보다 오래됐다 | Docker를 업데이트한다 |

### 로컬 Kafka (#318)

Kafka는 프로파일로 고릅니다(#530). `.env`의 `COMPOSE_PROFILES`로 정하고(`.env.example`에는 `single`), 둘 중 하나만 켭니다. 운영(`deploy/compose.prod.yml`)과 같은 두 구성이 있습니다.

| 프로파일 | 구성 | 메모리 | 쓰는 때 |
|---|---|---|---|
| `single` (기본) | 브로커 1대(`kafka`), 복제·최소 동기화 1 | `mem_limit` 1024m, swap 금지 | 평소. 인프라 자원 한계로 운영도 이 구성이다 |
| `cluster` | 브로커 3대(`kafka-1`~`kafka-3`), 복제 3·최소 동기화 2 | 한도 없음(Docker 메모리 약 2GB) | 인프라가 확장돼 3대를 쓸 때 다시 쓰려고 설정을 그대로 남겨 둔 구성 |

- 내 PC에서는 `single`이면 `localhost:19092`, `cluster`면 `19092`, `29092`, `39092`로 붙습니다. `127.0.0.1`에만 열려 있어 같은 네트워크의 다른 기기는 붙지 못합니다(인증 없는 PLAINTEXT라서). 두 프로파일은 같은 `KAFKA_1_PORT`를 쓰므로 함께 켜면 포트가 겹쳐 시작에 실패합니다.
- 앱의 접속 주소는 `monolith`의 `application.yaml`에 있는 `KAFKA_BOOTSTRAP_SERVERS`이고, 기본값의 첫 주소(`localhost:19092`)가 `single`의 브로커라 `.env`가 없어도 붙습니다. `.env`에서 `KAFKA_*_PORT`를 바꾸면 `KAFKA_BOOTSTRAP_SERVERS`도 같이 바꿉니다.
- 상태는 `docker compose ps`에서 `healthy`로 확인합니다. `cluster`는 3대 중 2대가 모여야 시작하므로 3대가 함께 뜹니다.
- `cluster`에서는 메시지가 3대에 복사되고 2대 이상에 저장돼야 발행 성공이라, 브로커 1대를 멈춰도(`docker compose stop kafka-2`) 발행·수신이 계속되고 다시 켜면(`docker compose start kafka-2`) 자동으로 합류합니다. `single`은 브로커가 멈추면 Kafka 전체가 멈춥니다.
- `single`에서 `cluster`로 바꾸려면(또는 반대로) 켜 둔 쪽을 먼저 내립니다(`docker compose down`). 볼륨이 서로 달라(`kafka-data`, `kafka-N-data`) 한쪽의 메시지는 다른 쪽에서 보이지 않습니다.
- Kafka만 처음 상태로 되돌리려면 `docker compose down -v`로 볼륨까지 지웁니다(토픽·메시지가 모두 사라지고 새 클러스터로 시작합니다).
  `docker-compose.yml`의 `CLUSTER_ID`를 바꿨거나 `KAFKA_VERSION`을 낮춰 브로커가 뜨지 않을 때도 이렇게 초기화합니다.
- `DOCKER_COMPOSE_ENABLED=true`로 앱이 compose를 띄울 때도 Kafka는 함께 뜹니다. Spring Boot는 Kafka 컨테이너로 접속 정보를 자동으로 만들지 않으므로, 주소는 항상 `KAFKA_BOOTSTRAP_SERVERS`로 정해집니다.
- Kafka 상태는 앱 헬스체크(`/actuator/health`, 관리 포트 18080)에 들어가지 않습니다. 앱은 메시지를 보내거나 받을 때 처음 브로커에 접속하므로, 브로커가 꺼져 있어도 기동은 됩니다.
- 메시지 키는 문자열, 값은 JSON입니다. 접속 주소와 직렬화·역직렬화 설정은 `common`의 [`common-kafka.yaml`](../common/src/main/resources/common-kafka.yaml) 한 곳에 있고, Kafka를 쓰는 앱(`monolith`, `refund`, `payout`)이 `spring.config.import`로 가져옵니다(#538).
  - **JSON 모양:** 날짜·시간은 ISO-8601 문자열(`"2026-10-30T14:00:00"`), 금액은 JSON 숫자이고, 값이 없는 필드도 이름을 남기고 `null`입니다. 보내는 쪽 클래스 이름(타입 헤더)은 싣지 않습니다.
  - **필드가 달라질 때:** 받는 쪽은 모르는 필드가 와도 읽고 빠진 필드는 `null`로 읽습니다. 그래서 보내는 쪽이 필드를 더해도 받는 쪽이 멈추지 않습니다. 필드 이름 변경·삭제나 타입 변경은 받는 쪽이 읽지 못하므로 두 모듈을 함께 바꿉니다.
  - **읽을 타입:** 타입 헤더가 없어 받는 쪽이 리스너(토픽)마다 정합니다(`@KafkaListener`의 `properties = "spring.json.value.default.type=..."`).
  - **읽을 수 없는 메시지:** JSON으로 읽지 못한 메시지는 예외를 던지지 않고(`ErrorHandlingDeserializer`) 컨슈머 오류 처리기로 넘어가 ERROR 로그를 남기고 건너뜁니다. 컨슈머가 같은 메시지에서 멈추지 않고, 원본 바이트는 오류 헤더에 남습니다. 보관(DLT)은 #388에서 정합니다.
  - 이 동작은 `KafkaSerializationTest`(common)와 `PayoutKafkaReceiveIntegrationTest`가 확인합니다.
- 의존성(`spring-boot-starter-kafka`)은 `common`이 `api`로 모든 모듈에 노출합니다. 모듈을 단독 실행하며 Kafka를 쓰게 되면 그 모듈 yaml의 `spring.config.import`에 `classpath:common-kafka.yaml`을 더합니다(설정을 복사하지 않습니다). 직렬화 설정이 앱마다 다르면 보내는 쪽과 받는 쪽이 메시지를 서로 읽지 못하기 때문입니다.

### 로컬 모니터링 (#374)

앱 지표(Prometheus), MySQL 지표(mysqld-exporter), 컨테이너 지표(cAdvisor)를 Grafana로 봅니다. `monitoring` 프로파일이라 기본 `docker compose up -d`에는 뜨지 않습니다. **`--profile`을 주면 `.env`의 `COMPOSE_PROFILES`가 대신 무시되므로**, Kafka 프로파일(`single`)도 함께 적어야 Kafka가 같이 뜹니다. `.env`에 `COMPOSE_PROFILES=single,monitoring`으로 적어 두면 `docker compose up -d`만으로 같습니다. 운영(`docs/DEPLOY.md` 11절)과 같은 설정 파일(`deploy/monitoring/`)을 씁니다.

```bash
docker compose --profile single --profile monitoring up -d   # 기본 서비스와 Kafka에 Prometheus, Grafana, mysqld-exporter, cAdvisor를 더해 실행
deploy/monitoring/create-exporter-user.sh       # 처음 한 번(비밀번호를 바꿀 때도). 로컬은 아래 COMPOSE_FILE을 붙인다
```

- 계정 스크립트는 로컬에서 `COMPOSE_FILE=../docker-compose.yml COMPOSE_PROFILES=monitoring deploy/monitoring/create-exporter-user.sh`로 실행합니다. 몇 번 실행해도 같은 결과이고, `.env`의 `MYSQL_EXPORTER_PASSWORD`로 `exporter` 계정(조회 전용)을 만들거나 비밀번호를 맞춥니다. 계정이 없으면 MySQL 지표(`mysql_up`)만 0입니다.
- Grafana: `http://localhost:3000` (`admin` / `.env`의 `GRAFANA_ADMIN_PASSWORD`, 기본 `admin`), Prometheus: `http://localhost:9090`. 둘 다 `127.0.0.1`에만 열립니다. 대시보드 `paldo-gotgan 개요`가 자동 등록됩니다.
- 앱 지표는 컨테이너로 띄운 앱(`--profile app`)의 관리 포트(`app:18080`)를 가져옵니다. 운영과 같은 주소입니다. IDE나 `:monolith:bootRun`으로 내 PC에서 앱을 띄우는 경우에는 `deploy/monitoring/prometheus/targets/local/app.yml`의 대상을 `host.docker.internal:18080`으로 바꿉니다(재시작 없이 반영되고, 이 변경은 커밋하지 않습니다).
- 대시보드는 위에서부터 주요 지표(트래픽·지연 P50/P95/P99·에러 4xx/5xx·포화도) 현재값과 색 임계값, API별 p95 상위 10 표, 원인 분석(JVM·커넥션 풀), 컨테이너 포화도, MySQL 순서입니다. 색 임계값은 글의 기준(CPU 80%, 풀 사용률 90%)과 시작값이라 서비스의 평소 값에 맞춰 `deploy/monitoring/grafana/dashboards/overview.json`에서 조정합니다. 앱 재시작은 그래프에 주황색 마커로 표시됩니다(`process_start_time_seconds` 변화).
- 컨테이너 CPU 스로틀링은 cAdvisor가 모읍니다(`monitoring` 프로파일에 함께 뜸, 호스트 포트 없음, 대상은 `deploy/monitoring/prometheus/targets/local/cadvisor.yml`). 스로틀링은 CPU 사용률이 낮아 보여도 CPU limit에 걸려 멈춘 비율이라 P99를 키웁니다. 컨테이너 CPU limit은 서비스에 `cpus`를 주어야 생기고(기본으로는 어느 서비스에도 걸려 있지 않음), limit이 없는 컨테이너는 스로틀 비율이 0이라 대시보드에 값이 없습니다(`-`).
- 스로틀링을 재현하려면 `docker run --rm --cpus 0.2 busybox sh -c 'while :; do :; done'`를 실행하고 대시보드의 `container` 변수에서 그 컨테이너를 고르면 스로틀 비율이 100%에 가깝게 오릅니다.
- Docker Desktop(Mac)에서는 cAdvisor가 도커 소켓을 직접 연결해야 컨테이너를 찾습니다(compose에 이미 연결해 둠). 이렇게 하면 `name` 라벨이 채워져 컨테이너 이름으로 고를 수 있음을 확인했습니다(Docker Desktop 4.92). 그래도 `name`이 비어 나오면 대시보드의 `container` 변수에 아무것도 뜨지 않으니, `container_cpu_cfs_throttled_periods_total` 같은 지표를 Prometheus에서 `id` 라벨(`/docker/<컨테이너 ID>`)로 직접 골라 봅니다. Linux(EC2)에서의 동작은 아직 확인하지 않았습니다.
- 슬로우 쿼리 로그(1초 이상)는 MySQL에 항상 켜져 있고(`docker compose exec mysql tail /var/lib/mysql/slow.log`), 개수는 대시보드의 MySQL 행에서 봅니다.
- 로컬 compose의 MySQL 서비스에 슬로우 쿼리 설정이 생겨서, 이 변경을 받은 뒤 처음 `docker compose up -d`를 하면 mysql 컨테이너가 한 번 다시 만들어집니다(`data/mysql`은 그대로).
- Grafana·Prometheus 데이터는 이름 있는 볼륨이라 `docker compose down -v`를 하면 지워집니다(Kafka 볼륨도 함께 지워지니 주의).

### 인프라 연결 확인 테스트 (#530)

compose로 띄운 실제 Redis·Kafka에 앱과 같은 접속 설정으로 붙는지 확인합니다. `common`의 공통 기반(`common-redis.yaml`, Kafka 접속 환경변수)을 확인하는 테스트라 `common`에 있습니다(`com.ok.common.config`의 `RedisInfraConnectionTest`, `KafkaInfraConnectionTest`). Testcontainers가 아니라 떠 있는 컨테이너를 쓰므로 `@Tag("infraTest")`로 표시해 기본 `test`(CI 포함)에서는 빠지고, 아래 태스크로만 돕니다.

```bash
docker compose up -d --wait redis kafka     # .env에 COMPOSE_PROFILES=single 이 있어야 redis와 kafka가 뜬다
./gradlew :common:infraTest
```

- Redis(`single` 기준, `REDIS_MODE`를 정하지 않은 상태): PING, 값 저장·조회, AOF 켜짐(`appendonly yes`, `everysec`), `noeviction`, `maxmemory`가 컨테이너 한도(512m)보다 작은지
- Kafka: 브로커 연결과 클러스터 조회, `KafkaTemplate`으로 발행한 메시지를 일반 컨슈머가 받는지(`single`과 `cluster` 모두 통과). 연결만 확인하므로 값은 문자열로 보내고, 앱의 JSON 직렬화는 따로 검증한다
- 접속 주소는 앱과 같이 `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` 환경변수로 바꿉니다. 인프라가 꺼져 있으면 실패합니다(Redis는 2초, Kafka는 10초 안에).

### 로컬 Kafka 지표 확인 (#396)

`monitoring` 프로파일로 띄우면 Kafka 지표 수집 컨테이너(`kafka-exporter`)가 함께 뜹니다. 브로커 수, 파티션 복제 상태, 컨슈머 그룹별 랙(아직 처리하지 않은 메시지 수)을 Prometheus 형식 숫자로 내놓습니다.

```bash
docker compose --profile single --profile monitoring up -d
# 지표 보기. 포트를 열지 않으므로 컨테이너 안에서 조회한다
# Kafka 내부 토픽(__consumer_offsets)은 파티션이 50개라 줄이 많아 뺀다
docker compose exec kafka-exporter wget -qO- localhost:9308/metrics | grep -E '^kafka_brokers|under_replicated|consumergroup_lag_sum' | grep -v __consumer_offsets
```

- 평소 개발(`docker compose up -d`)에는 뜨지 않습니다. 켜진 프로파일(`single`이면 브로커 1대, `cluster`면 3대)이 `healthy`가 된 뒤에 시작하고, exporter도 `docker compose ps`에서 `healthy`로 확인합니다.
- 기본은 `single`의 `kafka:9092`를 봅니다. `cluster`로 쓰면 `.env`에 `KAFKA_EXPORTER_SERVER_1=kafka-1:9092`, `KAFKA_EXPORTER_SERVER_2=kafka-2:9092`, `KAFKA_EXPORTER_SERVER_3=kafka-3:9092`를 넣습니다.
- 확인 명령과 헬스체크는 이미지 안의 `wget`(BusyBox)을 씁니다. `KAFKA_EXPORTER_VERSION`을 올리면 위 명령이 그대로 되는지 다시 확인합니다.
- 주로 보는 값
  - `kafka_brokers`: 지금 붙어 있는 브로커 수. 평소 `1`(`single`) 또는 `3`(`cluster`)
  - `kafka_topic_partition_under_replicated_partition`: 복사본이 모자란 파티션이면 `1`. 평소 모두 `0`
  - `kafka_consumergroup_lag_sum`: 그룹·토픽별 랙. 계속 커지면 받는 쪽이 따라가지 못하는 것
- `cluster`에서 브로커 1대를 멈추면(`docker compose stop kafka-2`) `kafka_brokers`가 `2`, 언더 복제가 `1`로 바뀌고, 다시 켜면(`docker compose start kafka-2`) 곧 `3`, `0`으로 돌아옵니다. `single`은 복사본이 없어 언더 복제가 항상 `0`입니다.
- 컨슈머 그룹이 하나도 없으면(앱을 띄우기 전) 랙 값은 나오지 않습니다.
- 띄운 직후 첫 조회에서 `Cannot get consumer group: broker not connected` 로그가 나올 수 있습니다. 브로커 연결이 준비되기 전이라 다음 조회부터는 나오지 않습니다.
- 그룹 오프셋은 조회만 하고 컨슈머 그룹에 가입하지 않으므로 앱 컨슈머에 영향을 주지 않습니다.
- Prometheus·Grafana 대시보드 연결은 #374 이후에 추가합니다.

### 로컬 Redis 클러스터 (#366)

`cluster` 프로파일(`.env`에 `COMPOSE_PROFILES=cluster`와 `REDIS_MODE=cluster`)일 때의 구성입니다. `single`(기본)은 Redis 1대라 이 절이 해당하지 않습니다. `docker compose up -d`가 Redis 노드 6대(`redis-1`~`redis-6`)를 띄우고, `redis-cluster-init`이 마스터 3 + 복제본 3으로 묶은 뒤 끝납니다. 노드가 모두 `cluster_state:ok`(healthy)가 되면 앱이 시작합니다. Docker 메모리는 Redis에 약 0.75GB를 더 잡아 둡니다(노드당 `mem_limit` 128m).

```bash
docker compose exec redis-1 redis-cli -p 7001 cluster info | grep cluster_state   # cluster_state:ok
docker compose exec redis-1 redis-cli -p 7001 cluster nodes                       # 노드 6대와 마스터·복제본 역할
redis-cli -c -h redis-1.localhost -p 7001 set k v                                 # 내 PC에서 접속 (-c는 슬롯 이동을 따라감)
```

- **접속 주소:** 노드가 클라이언트에게 알려 주는 주소로 앱이 다시 접속합니다. 그래서 노드 이름은 `redis-N.localhost`, 포트는 `7001`~`7006`으로 고정입니다(바꿀 수 없음). 내 PC에서는 `*.localhost`가 `127.0.0.1`로 풀리고, 컨테이너 안에서는 compose 네트워크 별칭으로 풀려 두 곳 모두 같은 값(`REDIS_CLUSTER_NODES`)으로 접속하도록 만들었습니다(컨테이너 안에서 `redis-cli`로 접속되는 것은 확인했습니다. 앱 컨테이너를 `--profile app`으로 처음 띄울 때 `/actuator/health`가 `UP`인지 확인합니다). `*.localhost`가 풀리지 않는 환경(일부 Linux·Windows)은 hosts 파일에 `127.0.0.1 redis-1.localhost` ~ `redis-6.localhost`를 추가합니다.
- **장애 조치 해 보기:** `cluster nodes`에서 마스터 하나를 찾아 `docker compose stop redis-N` 하면 몇 초 안에 그 복제본이 마스터가 됩니다(`cluster_state:ok` 유지). 다시 `docker compose start redis-N` 하면 복제본으로 합류하고, 노드 ID는 그대로입니다(`nodes.conf`가 볼륨에 있음). 단, **마스터 2대를 동시에 죽이면** 마스터 과반이 없어 자동 승격이 안 되고 클러스터 전체가 `CLUSTERDOWN`이 됩니다. 복구 방법은 `docs/DEPLOY.md`의 Redis 절을 봅니다.
- **처음부터 다시:** 노드 볼륨에는 노드 ID와 데이터가 들어 있습니다. 노드 일부의 볼륨만 지우면 `redis-cluster-init`이 클러스터를 만들지 않고 실패합니다(기존 데이터 보호). 6대를 모두 지우려면 `docker compose down -v`를 합니다(Kafka 볼륨도 함께 지워짐).
- 로컬 Redis는 비밀번호가 없고, 호스트 포트는 `127.0.0.1`에만 엽니다.

**클러스터에서 코드를 쓸 때의 규칙** (캐시·락 같은 사용처를 만드는 이슈에서 지킵니다)

- 여러 키를 한 번에 다루는 명령(`MGET`, `MSET`, 트랜잭션)과 Lua 스크립트는 키가 같은 슬롯에 있어야 합니다. 키에 해시 태그를 씁니다. 예: `order:{123}:lock`, `order:{123}:cache`는 `{123}`만 해시되어 같은 슬롯에 갑니다. 다른 슬롯의 키를 한 명령에 섞으면 `CROSSSLOT` 오류입니다.
- 읽기는 복제본을 우선합니다(`read-from: replicaPreferred`). 복제는 비동기라 **복제본은 마지막 쓰기보다 오래된 값을 줄 수 있습니다.** 그래서 락과 중복 방지는 "읽고 판단"하지 말고 `SET key value NX PX <ms>`나 Lua 스크립트 같은 원자적 명령으로 합니다(쓰기와 원자적 명령은 항상 마스터로 갑니다).
- **Redis는 락과 중복 방지의 정합성을 보장하지 않습니다.** 장애 조치 순간에는 마스터가 가진 최신 키가 새 마스터에 없을 수 있습니다. 락은 짧은 TTL로 쓰고, 정합성이 필요하면 DB 유니크 제약 같은 최종 방어선을 둡니다.
- 메모리 정책은 `noeviction`입니다(#319 그대로, 정책 재검토는 #324). 노드당 `maxmemory`가 64mb라 데이터가 차면 쓰기가 실패합니다.

### 앱까지 컨테이너(docker compose)로 실행

`docker compose up -d`는 DB, Redis, Kafka(`.env`의 `COMPOSE_PROFILES`)만 띄웁니다. 앱을 컨테이너로 같이 실행하려면 `app` 프로파일을 지정합니다. 이때도 `--profile`을 주면 `.env`의 `COMPOSE_PROFILES`가 무시되므로 `single`(또는 `cluster`)을 함께 적습니다.

먼저 JWT 키(`keys/`)를 만들어야 합니다. 앱이 기동할 때 키 파일을 읽는데, 이미지에는 키가 없고 compose가 저장소 루트의 `keys/`를 컨테이너의 `/app/keys`에 읽기 전용으로 연결합니다. 만드는 방법은 [8절 키 관리와 교체](#키-관리와-교체)의 "로컬 키 만들기"를 봅니다.

```bash
docker compose --profile single --profile app up -d --build   # 이미지 빌드 후 DB, Redis, Kafka, 앱 실행
docker compose --profile app logs -f app     # 앱 로그
docker compose down                          # 종료 (data/mysql 은 유지됨)
```

- DB, 켜진 프로파일의 Redis(`single`이면 1대, `cluster`면 6대와 `redis-cluster-init`)와 Kafka(`single`이면 1대, `cluster`면 3대)가 healthy 상태가 된 뒤에 앱이 시작됩니다. 앱 컨테이너는 Redis 주소를 compose가 정해 덮어씁니다. 기본(`single`)은 `REDIS_HOST=redis`, `REDIS_PORT=6379`이고(`.env`의 `REDIS_PORT`는 호스트 포트), `cluster`면 `.env`에 `REDIS_MODE=cluster`를 두고 노드 목록(`redis-N.localhost:700N`)은 compose가 고정합니다.
- 컨테이너 안의 앱은 `KAFKA_BOOTSTRAP_SERVERS`를 compose 네트워크의 브로커 이름으로 덮어씁니다. 기본은 `kafka:9092`(`single`)이고, `cluster`면 `.env`에 `KAFKA_INTERNAL_BOOTSTRAP_SERVERS=kafka-1:9092,kafka-2:9092,kafka-3:9092`를 넣습니다. `.env`의 `KAFKA_BOOTSTRAP_SERVERS`(`localhost:...`)는 컨테이너 안에서 앱 자신을 가리키기 때문에 쓰지 않습니다.
- Kafka 브로커는 `kafka-net`, MySQL·Redis는 `db-net`에 있고 앱 컨테이너만 두 네트워크에 함께 붙습니다.
- 앱의 DB 접속 정보(`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`)는 `.env`의 `MYSQL_*` 값에서 자동으로 만들어져 DB 설정과 항상 같습니다.
- `.env`의 나머지 값(`BATCH_JOB_ENABLED`, `APP_TIME_ZONE` 등)도 컨테이너에 전달됩니다. `.env`가 없으면 기본값을 씁니다.
- 호스트 포트는 `.env`의 `APP_PORT`(기본 8080)입니다. IntelliJ나 `:monolith:bootRun`으로 앱을 같이 실행 중이면 포트가 겹치니 하나만 실행하세요.
- 프로파일을 지정하지 않으면 `dev`로 실행됩니다.
- `keys/`가 없으면 앱이 `JWT 키 파일을 읽을 수 없습니다: URL [file:keys/rs256-v1-public.pem]` 오류로 기동에 실패합니다(`docker compose --profile app logs app`으로 확인). 8절의 명령으로 키를 만든 뒤 `docker compose --profile app up -d`로 다시 실행하면 됩니다. `keys/`가 없는 채로 실행하면 Docker가 빈 `keys/` 폴더를 만들 수 있으니 키 파일이 `keys/` 안에 있는지 확인합니다.
- `keys/`는 이미지에 들어가지 않습니다. `.dockerignore`가 빌드 컨텍스트에서도 제외합니다.

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
| `REDIS_VERSION` | Redis 이미지 버전 (docker compose, 통합 테스트 공통) | `8.10` |
| `REDIS_MODE` | 앱이 Redis에 붙는 방식. `single`(기본, 단일 Redis)이나 `cluster`. `cluster` 프로파일이면 `cluster`로 둔다(compose·운영은 deploy.sh가 정함) | `single` |
| `REDIS_HOST`, `REDIS_PORT` | 단일 Redis 주소(`REDIS_MODE`가 `single`일 때). `REDIS_PORT`는 compose의 호스트 포트이기도 하다 | `localhost`, `6379` |
| `REDIS_CLUSTER_NODES` | 앱이 접속할 Redis 클러스터 노드 목록(`REDIS_MODE=cluster`일 때, 쉼표 구분). 접속 후 나머지 노드와 역할은 앱이 스스로 읽는다. 노드 포트(7001~7006)는 노드가 알리는 주소와 같아야 해서 바꿀 수 없음(로컬 Redis 클러스터 절) | `redis-1.localhost:7001,...,redis-6.localhost:7006` |
| `REDIS_PASSWORD` | 앱의 Redis 비밀번호. 비어 있으면 없이 접속(로컬). 운영은 필수 | 빈 값 |
| `APP_PORT` | docker compose로 실행하는 앱 컨테이너의 호스트 포트 | `8080` |
| `CADVISOR_VERSION` | 모니터링 프로파일의 cAdvisor 이미지 태그 | `v0.60.6` |
| `GRAFANA_PORT` / `PROMETHEUS_PORT` | 모니터링 프로파일의 Grafana·Prometheus 호스트 포트 (`127.0.0.1`에만 열림) | `3000` / `9090` |
| `GRAFANA_ADMIN_PASSWORD` | Grafana 관리자(`admin`) 비밀번호. Grafana DB를 처음 만들 때만 쓰임 | `admin` |
| `MYSQL_EXPORTER_PASSWORD` | mysqld-exporter 전용 DB 계정(`exporter`) 비밀번호. `create-exporter-user.sh`가 이 값으로 계정을 만듦 | `exporterpassword` |
| `MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` / `MYSQL_ROOT_PASSWORD` | docker compose의 MySQL 계정 | `.env.example` 참고 |
| `DOCKER_COMPOSE_ENABLED` | 앱 실행 시 compose 자동 기동 (dev) | `false` |
| `BATCH_JOB_ENABLED` | 앱 시작 시 배치 Job 자동 실행 (dev, 운영도 `false` 고정. 켜면 Job 시작 전 검사에 걸려 앱이 시작되지 않음, #245·#485) | `false` |
| `MODULITH_REPUBLISH_ON_RESTART` | 재시작 시 미완료 이벤트 재발행 (dev, 운영은 `true` 고정) | `false` |
| `JPA_DDL_AUTO` | Hibernate `ddl-auto` (dev, 운영은 `validate` 고정) | `validate` |
| `APP_TIME_ZONE` | JDBC, Hibernate, Jackson 공통 타임존 | `Asia/Seoul` |
| `PROBLEM_BASE_URI` | 오류 응답 `type` 주소 접두사. `about:blank`이면 `type`을 `about:blank`로 둠 | `about:blank` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 앱의 DB 접속 정보 (직접 지정할 때) | 로컬 MySQL 기본값 |
| `KAFKA_VERSION` | Kafka 이미지 버전 (docker compose). 앱의 `kafka-clients` 버전과 같은 숫자 | `4.2.1` |
| `KAFKA_EXPORTER_VERSION` | Kafka 지표 수집 이미지 버전 (`monitoring` 프로파일) | `v1.11.0` |
| `COMPOSE_PROFILES` | 켤 compose 프로파일. Kafka와 Redis는 `single`(각 1대) 또는 `cluster`(브로커 3대, Redis 6대) 중 하나만 (#530). 모니터링은 `monitoring`을 쉼표로 더한다 | 없음(`.env.example`은 `single`). 비우면 Kafka·Redis가 뜨지 않음 |
| `KAFKA_1_PORT` / `KAFKA_2_PORT` / `KAFKA_3_PORT` | 내 PC에서 브로커에 붙는 포트 (`127.0.0.1`에만 열림). `single`은 `KAFKA_1_PORT`만 씀 | `19092` / `29092` / `39092` |
| `KAFKA_BOOTSTRAP_SERVERS` | 내 PC에서 실행하는 앱이 접속할 브로커. `single`이면 첫 주소만 맞으면 됨 | `localhost:19092,localhost:29092,localhost:39092` |
| `KAFKA_INTERNAL_BOOTSTRAP_SERVERS` | 컨테이너로 띄운 앱(`app` 프로파일)이 접속할 브로커. `cluster`일 때만 지정 | `kafka:9092` |
| `KAFKA_EXPORTER_SERVER_1` ~ `_3` | Kafka 지표 수집기가 볼 브로커. `cluster`일 때만 지정 | `kafka:9092` |
| `KAFKA_CONSUMER_GROUP_ID` | 컨슈머 그룹 ID. 같은 그룹끼리는 메시지를 나눠 받고 그룹이 다르면 각자 모두 받는다 | 앱 이름(`spring.application.name`, monolith는 `paldo-gotgan`) |

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
| `spring.data.redis.*` | `host`=`${REDIS_HOST:localhost}`, `port`=`${REDIS_PORT:6379}`, `${REDIS_PASSWORD:}`, 연결 2s·응답 1s. `REDIS_MODE=cluster`이면 `common-redis-cluster.yaml`이 더해져 `cluster.nodes`=`${REDIS_CLUSTER_NODES:...}`, 리다이렉트 3회, 복제본 우선 읽기, 토폴로지 30초 주기 갱신(리다이렉트·재연결 때는 즉시 갱신) | Redis 접속 정보와 타임아웃. 모듈 yaml에 직접 적지 않고 `common`의 `common-redis.yaml` 한 곳에 있습니다. 모듈 yaml에 직접 적지 않고 `common`의 `common-redis.yaml` 한 곳에 있습니다. 지금은 monolith만 `spring.config.import: classpath:common-redis.yaml`로 가져오고, 모듈은 Redis를 쓰기 시작할 때 자기 yaml의 import에 이 항목을 추가합니다. 값을 바꿀 때는 그 파일만 고칩니다. 리포지터리는 쓰지 않아 `repositories.enabled: false`입니다 |
| `trace.node-id` | `0` | traceId(Snowflake)에 들어가는 노드 번호(0~1023). 설정이 없으면 0이고, 범위를 벗어나면 기동이 실패합니다. 서버(인스턴스)를 둘 이상 띄울 때 서버마다 다르게 지정합니다. 자세한 내용은 [12. 추적](#12-추적-traceid) |
| `app.async.*` | `default-executor`, `executors.<이름>.*` | 비동기 실행기(가상 스레드)의 설정. `event` 실행기의 값은 `common`의 `common-async.yaml` 한 곳에 있고, 모듈이 쓰는 실행기는 이름을 붙여 추가합니다. 자세한 내용은 [11. 비동기 처리](#11-비동기-처리-이벤트-리스너와-async) |
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
- 도메인 모듈끼리는 `project(...)` 의존을 걸지 않습니다. 다른 도메인 모듈은 `build.gradle.kts`에 `project(...)`로 선언하지 않으면 컴파일할 수 없고, 선언은 PR diff에서 바로 보이므로 리뷰에서 확인합니다. 모듈 간 연동은 지금 `common.event`의 이벤트로 하고, 메시지 브로커로 옮긴 이벤트와 새 이벤트는 각 모듈의 `event` 패키지에 둔 DTO로 합니다.
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

### delivery 모듈 구조(헥사고날)

`delivery`는 모듈별 서버 배포와 브로커 연동 전환에 대비해 진입점과 외부 연결을 패키지로 나눈 헥사고날 구조입니다. 다른 모듈은 [CONVENTION.md](CONVENTION.md#2-패키지모듈-구조)의 구조 그대로입니다.

```
com.ok.delivery
├── domain/               엔티티, 값 객체, 정책 (엔티티는 JPA 어노테이션을 그대로 가진다)
├── exception/            DeliveryErrorCode
├── config/               모듈 전용 설정
├── application/
│   ├── port/in/          입력 포트: 유스케이스 인터페이스 (*UseCase)
│   ├── port/out/         출력 포트: 저장소, 이벤트 발행, 택배사 인터페이스
│   ├── service/          유스케이스 구현 (*Service, *Facade)
│   └── dto/              *Command, *Query, *Result
└── adapter/
    ├── in/web/           컨트롤러, *Spec, 요청·응답 DTO(dto/)
    ├── in/event/         이벤트 리스너, 택배사 알림 이벤트(CarrierDeliveredEvent)
    ├── out/persistence/  Spring Data JPA 저장소, Querydsl 조회
    ├── out/event/        스프링 이벤트 발행 구현체
    └── out/carrier/      가짜 택배사(MockCarrierClient)
```

- 의존 방향은 `adapter.in` → `application`(`port.in`, `dto`) → `domain`, `adapter.out` → `application.port.out`, `domain`입니다. 서비스는 JPA 저장소를 모르고 `port.out` 인터페이스에만 의존합니다.
- 컨트롤러와 이벤트 리스너는 `port.in`의 유스케이스 인터페이스만 압니다. 서비스 구현체, `port.out`, `domain`을 직접 쓰지 않습니다. 기본 배송지는 재시도를 맡는 `DefaultShippingAddressFacade`가, 택배사 추적 요청은 트랜잭션 없는 `CarrierTrackingFacade`가 유스케이스를 구현합니다.
- 출력 포트 이름은 `*Repository`(조회 전용은 `*QueryRepository`)이고, 구현은 `adapter/out/persistence`에 있습니다. Spring Data 인터페이스(`*JpaRepository`)가 포트를 상속해 구현하므로 실행 객체는 Spring Data 프록시 그대로이고, 잠금·트랜잭션·쿼리 동작은 서비스가 JPA 저장소를 직접 쓸 때와 같습니다. 포트에는 Spring Data `Repository` 상속과 JPA 어노테이션을 두지 않고, 잠금 조회(`@Lock`, `@Query`)는 JPA 저장소에서 다시 선언합니다. 포트는 Spring Data의 `Page`, `Pageable`은 씁니다.
- 모듈 간 연동은 지금처럼 Spring 이벤트와 `/internal` 호출입니다. 이벤트 수신은 `adapter/in/event`, 발행은 `adapter/out/event`에 있어서, 연동 방식이 바뀌어도 서비스는 바뀌지 않습니다. `common.event`의 이벤트 클래스와 `ApplicationEventPublisher`는 `adapter`에서만 씁니다.
- 이 구조는 `delivery`의 `ArchitectureTest`가 검사합니다(레이어 방향, 입력 어댑터 제한, application의 영속 기술 의존 금지, 포트 규칙, 이름·위치, `common` 외 다른 모듈 의존 금지).

### refund 모듈 구조(헥사고날)

`refund`도 헥사고날 구조입니다. 패키지와 의존 규칙은 환불 전용 규칙 `refund/CLAUDE.md`와 refund `ArchitectureTest`를 따릅니다.

### wallet 모듈 구조(헥사고날)

`wallet`은 연동이 Kafka로 바뀌고 토스 결제 조회·취소가 늘어날 것에 대비해, 진입점과 외부 연결을 delivery와 같은 헥사고날 구조로 나눴습니다.

```
com.ok.wallet
├── domain/               엔티티 (JPA 엔티티를 도메인 모델로 그대로 쓴다)
├── exception/            WalletErrorCode
├── config/               모듈 전용 설정
├── application/
│   ├── port/in/          입력 포트: 유스케이스 인터페이스 (*UseCase)
│   ├── port/out/         출력 포트: 저장소, 이벤트 발행, 토스 결제 승인 인터페이스
│   ├── service/          유스케이스 구현 (*Service)
│   └── dto/              *Command, *Query, *Result, 토스 승인 결과(TossConfirmResult)
└── adapter/
    ├── in/web/           컨트롤러, *Spec, 요청·응답 DTO(dto/)
    ├── in/event/         이벤트 리스너
    ├── out/persistence/  저장소 포트 구현(*RepositoryAdapter), Spring Data JPA 저장소, Querydsl 조회
    ├── out/event/        스프링 이벤트 발행 구현체
    └── out/toss/         토스 결제 승인 HTTP 구현, 토스 요청·응답 DTO, 설정
```

- 의존 방향, 이벤트 수신·발행 위치는 delivery와 같습니다.
- 저장소 포트는 delivery와 달리 `*JpaRepository`가 상속하지 않고, 어댑터 클래스(`*RepositoryAdapter`)가 구현해 `*JpaRepository`에 넘깁니다. 어댑터는 트랜잭션을 열지 않으므로 잠금과 쿼리는 서비스의 트랜잭션 안에서 지금과 같이 동작합니다. 저장소 메서드를 추가하면 포트, 어댑터, `*JpaRepository` 세 곳을 고칩니다.
- 어댑터에는 `@Repository`가 아니라 `@Component`를 붙입니다. DB 예외 변환은 안쪽 `*JpaRepository` 프록시가 이미 하므로 다시 할 필요가 없습니다.
- 통합 테스트는 데이터를 준비·정리할 때 포트에 없는 메서드(`saveAndFlush`, `deleteAll` 등)를 쓰므로 `*JpaRepository`를 주입받습니다. `@DataJpaTest`로 포트를 쓰는 테스트는 어댑터를 `@Import`로 등록합니다.
- 토스 승인은 출력 포트(`TossPaymentsClient`)로 부릅니다. 서비스는 승인 결과(`TossConfirmResult`)만 알고, HTTP 요청·응답 모양은 `adapter/out/toss` 안에 둡니다.
- 충전 승인은 토스를 부르는 `ChargeConfirmService`(트랜잭션 없음)만 `ChargeConfirmUseCase`를 구현합니다. DB 반영은 `ChargeTransactionService`가 포트 없이 다른 빈으로 맡습니다. 같은 빈 안에서 부르면 `@Transactional`이 걸리지 않으므로 두 서비스를 합치지 않습니다.
- 엔티티를 도메인 모델과 나누지 않습니다. 잔액 변경과 충전 완료는 엔티티 값만 바꾸고 저장은 변경 감지에 맡기므로, 나누면 저장이 빠졌을 때 오류 없이 잔액이 반영되지 않습니다.
- 입력 포트는 엔티티를 돌려주지 않습니다. 잠긴 엔티티가 필요한 `getOrCreate`는 서비스에만 두고 `application` 안에서만 씁니다.
- 잠금 조회는 이름에 `ForUpdate`/`ForShare`를 넣고, `*JpaRepository`에만 `@Lock`을 붙입니다(`ForUpdate`는 `PESSIMISTIC_WRITE`, `ForShare`는 `PESSIMISTIC_READ`). 어댑터가 `@Lock`이 붙은 메서드를 직접 부르므로 포트 쪽에서 다시 선언하는 단계가 없습니다.
- 이 구조는 `wallet`의 `ArchitectureTest`가 검사합니다(delivery 규칙에 더해 application의 HTTP 클라이언트 의존 금지, 입력 포트의 도메인 의존 금지, JPA 저장소의 포트 상속 금지, 잠금 조회 이름과 `@Lock` 종류가 맞는지, 어댑터의 잠금 조회가 같은 종류의 `@Lock` 메서드만 부르는지 검사).

### member 모듈 구조(헥사고날)

`member`는 모듈별 서버 배포와 Kafka 연동에 대비해(#475) 하위 도메인(`seller`, `member`, `auth`)을 하나씩 헥사고날 구조로 옮겼습니다(#476~#479). 진입점은 유스케이스 포트만, 서비스는 출력 포트만 알고, 하위 도메인 사이 호출은 출력 어댑터에 모읍니다.

```
com.ok.member.seller
├── domain/               엔티티 (SellerApplication, SellerProfile)
├── exception/            SellerErrorCode
├── application/
│   ├── port/in/          입력 포트: 유스케이스 인터페이스 (*UseCase)
│   ├── port/out/         출력 포트: 저장소, 이벤트 발행
│   ├── service/          유스케이스 구현 (*Service), 검증기 (*Validator)
│   └── dto/              *Command, *Query, *Result
└── adapter/
    ├── in/web/           컨트롤러, *Spec, 요청·응답 DTO(dto/)
    ├── out/persistence/  Spring Data JPA 저장소 (*JpaRepository)
    └── out/event/        스프링 이벤트 발행 구현체
```

`member` 하위 도메인은 여기에 `adapter/in/bootstrap`(앱이 시작될 때 설정값으로 최초 관리자 계정을 만드는 설정·실행기)이 더 있습니다. 앱 시작이라는 외부 입력의 진입점이라 컨트롤러처럼 유스케이스만 부릅니다.

`auth` 하위 도메인은 저장소 말고도 출력 포트가 둘 더 있습니다.

```
com.ok.member.auth
├── config/               JWT 서명 키 설정, 비밀번호 인코더
├── application/
│   ├── port/in/          LoginUseCase, TokenReissueUseCase
│   ├── port/out/         RefreshTokenRepository, AccessTokenIssuer, MemberCredentialReader, SellerApprovalReader
│   └── service/          LoginFacade(트랜잭션 밖 비밀번호 확인), AuthService(트랜잭션), RefreshTokenService
└── adapter/
    ├── in/web/           AuthController, JwksController
    ├── out/persistence/  RefreshTokenJpaRepository
    ├── out/token/        JwtAccessTokenIssuer (JWT 서명)
    └── out/member/       MemberAuthAdapter (member·seller 유스케이스 호출)
```

- 로그인 경로는 #425 규칙을 그대로 따릅니다. `LoginFacade`에는 `@Transactional`을 붙이지 않고 저장소와 출력 포트를 직접 부르지 않습니다. 비밀번호 확인 없이 토큰을 내주는 `AuthService`의 두 메서드(`findLoginCredential`, `issueLoginTokens`)는 입력 포트에 넣지 않고 package-private으로 두므로, `LoginFacade`와 `AuthService`는 같은 `application/service` 패키지에 있어야 합니다.
- JWT 서명(`JwtEncoder`, `JwsHeader`)은 `adapter/out/token`에서만 씁니다. `ArchitectureTest`가 `application`의 `org.springframework.security.oauth2.jwt`, `com.nimbusds` 의존을 막습니다.

- 의존 방향은 `adapter.in` → `application`(`port.in`, `dto`) → `domain`, `adapter.out` → `application.port.out`, `domain`입니다. 컨트롤러는 `port.in`의 유스케이스 인터페이스만 알고, 서비스는 JPA 저장소를 모르고 `port.out` 인터페이스에만 의존합니다.
- 저장소 포트(`*Repository`)는 Spring Data `Repository`를 상속하지 않습니다. `adapter/out/persistence`의 Spring Data 인터페이스(`*JpaRepository`)가 포트를 상속해 구현하므로 실행 객체는 Spring Data 프록시 그대로입니다.
- `application`은 JPA와 Spring Data 저장소에 의존하지 않지만, Spring의 `Page`, `Pageable`, `Sort`와 DAO 예외(`DataIntegrityViolationException`, `OptimisticLockingFailureException`)는 씁니다. 영속 기술이 아닌 Spring 추상화라 허용하고, `ArchitectureTest`도 막지 않습니다.
- 이벤트 발행은 `port.out`의 발행 인터페이스로 하고, 발행 도구(`ApplicationEventPublisher`, `KafkaOperations`)와 `common.event`의 이벤트 클래스는 `adapter/out/event`의 구현체에서만 씁니다. 발행을 Kafka로 바꿔도 구현체가 출력 포트를 구현해야 합니다.
- 다른 하위 도메인은 그 하위 도메인의 `port.in`과 `dto`만, 그것도 자기 `adapter/out`에서만 씁니다. 서비스는 자기 출력 포트만 알고, 출력 어댑터가 상대 유스케이스를 불러 자기 DTO로 바꿉니다. 예를 들어 `auth`의 `AuthService`는 `MemberCredentialReader`, `SellerApprovalReader` 포트만 쓰고, `adapter/out/member/MemberAuthAdapter`가 `MemberAuthAccountUseCase`(로그인 정보와 관리자·탈퇴·정지 여부)와 `SellerApprovalCheckUseCase`(판매자 승인 여부)를 부릅니다. 유스케이스는 엔티티나 도메인 enum 대신 판단 결과를 값으로 돌려줍니다. 하위 도메인 사이 의존이 출력 어댑터 한 곳에 모여, 상대 하위 도메인의 유스케이스나 DTO가 바뀌어도 서비스는 바뀌지 않습니다.
- 다른 하위 도메인을 부르는 출력 어댑터는 부르는 대상 이름으로 `adapter/out/<대상>` 패키지에 두고, `<대상><용도>Adapter`로 짓습니다(예: `auth/adapter/out/member/MemberAuthAdapter`). 같은 member 모듈인 `member`와 `seller`를 함께 부르면 어댑터 하나로 묶습니다. `order`의 장바구니처럼 같은 모듈 안의 유스케이스를 바로 부르지 않고 어댑터를 두는 이유는, 서비스가 다른 하위 도메인의 타입을 모르게 해 의존을 한 곳에서 보이게 하기 위해서입니다.
- 하위 도메인을 서버로 나눌지는 정하지 않았습니다. 이 경계가 있어도 서버를 나누면 어댑터 교체만으로 끝나지 않습니다. 비밀번호 해시를 API로 내보내지 않도록 비밀번호 확인 위치를 옮기고, 같은 트랜잭션 안에서 하던 계정 확인과 원격 호출의 장애 처리를 다시 정해야 합니다. 분리를 검토할 조건과 방식은 #478의 결정 기록(`docs/decisions/member/`)에 있습니다.
- 포트와 `JpaRepository`가 같은 메서드(`findById`, `save`, `saveAndFlush`)를 서로 다른 제네릭 형태로 물려주어, `*JpaRepository` 타입으로 부르면 모호한 호출 오류가 납니다. 이런 메서드는 `*JpaRepository`에서 다시 선언해 하나로 합칩니다.
- 통합 테스트는 데이터를 준비·정리할 때 포트에 없는 메서드(`deleteAll` 등)를 쓰므로 `*JpaRepository`를 주입받습니다.
- 이 구조는 `member`의 `ArchitectureTest`가 하위 도메인 목록(`SUBDOMAINS`)마다 같은 규칙으로 검사합니다(레이어 방향, 입력 어댑터 제한, 입력 포트의 도메인 의존 금지, application의 영속·토큰 서명 기술 의존 금지, 포트 규칙, 이름·위치, 다른 하위 도메인은 `adapter.out`에서 `port.in`과 `dto`만 사용, 잠금 조회의 `@Lock` 재선언, `LoginFacade`의 트랜잭션·출력 포트 의존 금지). 모듈 최상위 패키지(`com.ok.member`)는 다른 모듈에 공개하는 클래스 자리라 유스케이스를 쓸 수 있습니다. 이벤트 발행 구현체 규칙은 하위 도메인 목록 대신 모듈 전체에 걸어, 이벤트를 발행하지 않는 하위 도메인(`auth`)도 발행을 시작하면 따로 등록하지 않아도 검사됩니다.
- 새 하위 도메인을 만들면 `SUBDOMAINS`에 더합니다. 목록 밖 패키지는 위 규칙이 검사하지 않으므로, 하위 도메인·모듈 최상위·`config` 밖에 클래스가 생기면 `ArchitectureTest`가 실패합니다.

### order 모듈 구조(헥사고날)

`order`는 하위 도메인 `cart`(장바구니), `order`(주문)로 나뉘고, 두 하위 도메인 모두 `delivery`와 같은 헥사고날 구조입니다. 규칙은 위 delivery 구조와 같고, 아래만 다릅니다.

```
com.ok.order
├── config/                    모듈 전용 설정
├── cart/
│   ├── domain/, exception/
│   ├── application/           port/in, port/out(저장소), service, dto
│   └── adapter/               in/web, in/event, out/persistence
└── order/
    ├── domain/, exception/
    ├── application/
    │   ├── port/in/           주문서·주문 생성·조회·구매확정·결제 결과·배송 상태·리뷰 후보·환불용 주문 상품 조회·환불 취소 반영 유스케이스
    │   ├── port/out/          OrderRepository, OrderEventPublisher, ProductPort, DeliveryPort, WalletPort
    │   ├── service/           OrderFacade(외부 호출 조율), OrderService(트랜잭션)
    │   └── dto/
    └── adapter/
        ├── in/web/            컨트롤러, *Spec, 요청·응답 DTO
        ├── in/event/          지갑·배송 이벤트 리스너
        ├── in/scheduler/      자동 구매확정 스케줄러
        ├── out/persistence/   OrderJpaRepository
        ├── out/event/         스프링 이벤트 발행 구현체
        ├── out/product/       상품 모듈 내부 API 호출 (ProductPort 구현)
        ├── out/delivery/      배송 모듈 내부 API 호출 (DeliveryPort 구현)
        ├── out/wallet/        지갑 모듈 내부 API 호출 (WalletPort 구현)
        └── out/http/          내부 API 호출용 RestClient 공통 설정
```

- 다른 모듈 호출은 대상 모듈마다 출력 포트(`ProductPort`, `DeliveryPort`, `WalletPort`) 하나를 두고, 구현은 `adapter/out/<대상>`에 둡니다. 상대 모듈의 요청·응답 모양(`*Request`, `*Response`)은 어댑터 안에만 두고, 포트는 주문의 DTO(`ProductOption`, `StockItem`)나 도메인 값(`SellerShippingFee`)을 주고받습니다. HTTP를 메시지 브로커나 다른 방식으로 바꿔도 어댑터만 바뀝니다.
- 주문은 같은 모듈의 장바구니를 어댑터 없이 장바구니의 `port.in` 유스케이스와 `application.dto`로만 씁니다. 같은 프로세스 안의 호출이라 어댑터를 두면 변환만 늘기 때문입니다. 장바구니의 서비스, 출력 포트, 어댑터, 도메인은 쓰지 않습니다.
- 테스트에서 상품·배송·지갑 호출은 `testsupport`의 Fake 출력 포트(`FakeProductPort` 등)가 대신합니다. HTTP 응답 변환은 어댑터 DTO 단위 테스트로 확인합니다.
- 이 구조는 `order`의 `ArchitectureTest`가 두 하위 도메인에 같은 규칙으로 검사합니다(`com.ok.order.*.<레이어>`).

### payout 모듈 구조(헥사고날)

`payout`도 헥사고날 구조입니다. 패키지와 의존 규칙은 정산 전용 규칙 `payout/CLAUDE.md`와 payout `ArchitectureTest`를 따릅니다.

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
| `auth-key-unavailable` | 503 | 검증 공개키를 발급 모듈에서 받아 오지 못함 → 토큰을 그대로 두고 잠시 후 재시도 ([공개키 배포 (JWKS)](#공개키-배포-jwks)) |
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
- **`kid`로 키 선택**: 디코더는 토큰 헤더의 `kid`에 해당하는 공개키(`jwt.verification-keys`)로 검증하고, `kid`가 없거나 목록에 없는 토큰은 거부합니다.
- 서명 알고리즘과 키는 `JwtConfig`(검증), `JwtEncoderConfig`(발급)에서만 관리하므로 각 모듈의 인가 코드는 알고리즘과 무관합니다.
- 검증 앱은 공개키를 파일로 갖거나, member가 내주는 JWKS에서 받아 씁니다(#314). 아래 [공개키 배포 (JWKS)](#공개키-배포-jwks)를 봅니다.

### 공개키 배포 (JWKS)

member(auth)는 검증용 공개키 목록(`jwt.verification-keys` 전체)을 `GET /.well-known/jwks.json`으로 내줍니다(RFC 7517 형식, 개인키 값은 빼고). 검증 앱에 `jwt.jwk-set-uri`를 설정하면 키 파일 없이 이 주소에서 공개키를 받아 검증합니다. 모듈을 따로 배포하면 키를 교체할 때 member만 고치고, 다른 앱은 재배포하지 않아도 새 공개키를 받아 갑니다.

| 앱 | 검증 키 출처 | 이유 |
|---|---|---|
| monolith (운영) | 로컬 공개키 파일 | member가 같은 앱 안에 있어 HTTP로 부를 필요가 없음 |
| member 단독 실행 | 로컬 공개키 파일 | 발급하는 앱. `jwk-set-uri`를 설정하면 기동하지 않습니다 |
| 다른 모듈 앱 (기본) | 로컬 공개키 파일 (`keys/rs256-v1-public.pem`) | member를 띄우지 않아도 개발할 수 있게 |
| 다른 모듈 앱 + `JWT_JWK_SET_URI` | member의 JWKS | 분리 배포용. 이때 `verification-keys`는 읽지 않아 키 파일이 없어도 됩니다 |

**동작**

- 앱이 뜰 때는 JWKS를 부르지 않고, 첫 검증 요청 때 받아 와 메모리에 캐시합니다(앱마다, 서버 한 대마다 따로). member가 떠 있지 않아도 다른 앱은 기동합니다.
- 캐시 시간(기본 5분) 동안은 member를 다시 부르지 않습니다. 처음 보는 `kid`의 토큰이 오면 캐시 시간이 남아 있어도 바로 다시 받아 오고, 그래도 없는 `kid`면 401입니다.
- member가 멈추거나 응답이 늦어 키를 받아 오지 못하면 401이 아니라 **503 `auth-key-unavailable`**입니다. 토큰에는 문제가 없으므로, 클라이언트는 토큰을 버리거나 재발급하지 말고 잠시 후 다시 보냅니다. 캐시가 남아 있는 동안(최대 5분)은 member가 멈춰도 계속 검증되고, member가 살아나면 다음 요청이 키를 받아 와 저절로 정상이 됩니다.
- 다시 받아 오는 횟수는 제한하지 않습니다. 그래서 `kid`를 지어낸 토큰 하나마다 member 호출이 하나 생깁니다. 들어온 요청 수만큼만 생기므로 위험은 일반적인 요청 폭주와 같은 수준입니다(이유는 `JwtConfig.remoteKeySource` 주석).
- 외부에서는 이 주소를 부를 수 없습니다. nginx는 `/api/v1/`만 앱으로 넘기고 나머지는 404입니다. nginx 설정을 바꿀 때도 `/.well-known/`을 넘기지 않습니다.

**설정** (검증 앱의 `application-<모듈>.yaml`)

| 키 | 기본값 | 설명 |
|---|---|---|
| `jwt.jwk-set-uri` | 비어 있음 (`${JWT_JWK_SET_URI:}`) | member의 JWKS 주소(`http`/`https`). 있으면 `verification-keys`보다 우선합니다. 응답을 중간에서 바꿀 수 있으면 공개키를 바꿔 끼워 토큰을 위조할 수 있으므로, `http`는 같은 Docker 네트워크처럼 신뢰할 수 있는 내부망에서만 쓰고 다른 호스트로 나누면 `https`를 씁니다 |
| `jwt.jwk-set.cache-ttl` | `5m` | 받아 온 키를 캐시하는 시간. member는 자기 설정값을 응답의 `Cache-Control: max-age`로 내보내지만 안내용이고, 검증 앱은 이 헤더가 아니라 자기 `cache-ttl`을 따릅니다 |
| `jwt.jwk-set.connect-timeout`, `read-timeout` | `2s`, `2s` | member 연결·응답을 기다리는 시간. 넘으면 그 요청은 503입니다 |

**로컬에서 원격 모드로 실행하기**

```bash
./gradlew :member:bootRun                                               # 터미널 1 (8082)
curl -s localhost:8082/.well-known/jwks.json                            # 공개키 목록 확인
JWT_JWK_SET_URI=http://localhost:8082/.well-known/jwks.json ./gradlew :order:bootRun   # 터미널 2 (8084)
```

order 기동 로그에 `JWT 검증 키: 원격 JWKS uri=http://localhost:8082/...`가 나오면 원격 모드입니다(로컬이면 `로컬 공개키 kid=[...]`). member에서 로그인한 토큰으로 order API를 호출합니다. `JWT_JWK_SET_URI`를 루트 `.env`에 넣으면 모든 모듈 앱이 원격 모드가 되므로, 필요한 실행에만 환경변수로 넘기세요.

운영에서 모듈을 따로 배포할 때의 설정(compose, 발급 모듈 내부 주소)과 member 장애가 캐시 시간보다 길 때의 대비는 분리 배포 작업에서 정합니다.

### 키 관리와 교체

**설정**

| 키 | 두는 앱 | 설명 |
|---|---|---|
| `jwt.verification-keys[].key-id`, `public-key` | `jwk-set-uri`가 없는 모든 앱 | 검증용 공개키 목록. `public-key`는 X.509 PEM 파일 위치(`file:`, `classpath:`). member에서는 이 목록 전체가 JWKS로 공개됩니다 |
| `jwt.signing.key-id`, `private-key` | member, monolith | 서명용 개인키. `private-key`는 PKCS#8 PEM 파일 위치. `key-id`는 검증 목록에 있어야 하고, 개인키가 그 공개키와 짝이 아니면 기동하지 않습니다 |

- 로컬은 저장소 루트의 `keys/rs256-v1-public.pem`, `keys/rs256-v1-private.pem`을 씁니다(`keys/`는 `.gitignore`에 있음). 다른 위치를 쓰려면 `.env`에 `jwt.signing.private-key=file:/절대경로.pem`처럼 설정 키를 직접 적습니다.
- IDE에서 `main`을 바로 실행하면 `bootRun`과 달리 저장소 루트를 모르므로 키 파일을 찾지 못해 기동에 실패합니다. 실행 설정의 작업 디렉터리를 저장소 루트로 두거나, 모듈 앱은 환경 변수 `OK_ROOT_DIR`에 저장소 루트를 넣습니다.
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
- 이 지름길은 토큰을 발급하는 인스턴스가 하나일 때만 안전합니다. member나 monolith를 2대 이상 띄우거나 롤링·카나리로 배포하면, 각 단계를 모든 인스턴스에 롤아웃한 뒤 다음 단계로 넘어가고 1과 2를 한 배포에 합치지 않습니다. 합치면 롤아웃 중 새 키로 서명한 토큰을 아직 새 키가 없는 인스턴스의 JWKS로 검증하게 되어 401이 됩니다. 다음 단계로 넘어가기 전에 인스턴스마다 `/.well-known/jwks.json`의 `kid` 목록이 같은지 확인합니다.
- 검증 앱이 JWKS를 쓰면 1은 member의 `verification-keys`에만 추가하고 member를 재시작합니다. 다른 앱은 바꾸지 않습니다. 2는 캐시 시간(기본 5분) 이상 기다린 뒤 합니다. 처음 보는 `kid`는 바로 다시 받아 오므로 기다리지 않아도 대개 동작하지만, 모든 앱이 새 키를 받은 상태에서 서명을 바꾸는 편이 안전합니다. 3도 member만 고칩니다.
- 개인키가 유출되었으면 3을 기다리지 않고 옛 키를 바로 뺍니다. 그 키로 서명된 토큰이 모두 거부되고, 사용자는 Refresh Token으로 재발급받습니다. Refresh Token은 JWT가 아니라서 영향이 없습니다.
  검증 앱이 JWKS를 쓰면 member에서 뺀 키도 검증 앱의 캐시 시간(기본 5분)이 끝날 때까지는 계속 통과합니다. 바로 막아야 하면 검증 앱을 재시작해 캐시를 비웁니다.

### 토큰 재발급

Access Token(30분)이 만료되면 로그인 때 받은 Refresh Token으로 새 토큰을 받습니다.

1. API가 401 `token-expired`를 반환하면 `POST /api/v1/auth/reissue`에 `{"refreshToken": "..."}`를 보냅니다.
2. 성공하면 응답의 **새 Access Token과 새 Refresh Token으로 둘 다 교체**하고, 실패한 요청을 다시 보냅니다. 사용한 Refresh Token은 다시 쓸 수 없습니다.
3. 재발급이 401(`auth/invalid-refresh-token`)이면 다시 로그인합니다. 403(`auth/member-suspended`)은 정지된 회원입니다.
- 재발급할 때 회원 정보를 다시 읽어 최신 권한으로 발급합니다. 판매자 승인 같은 권한 변경도 재로그인 없이 반영됩니다.
- 같은 Refresh Token으로 동시에 요청하면 하나만 성공합니다. 여러 요청이 동시에 `token-expired`를 받아도 **재발급은 한 번만** 보내고, 나머지 요청은 그 결과를 기다렸다가 새 토큰으로 다시 보내야 합니다.
- 재발급 요청은 Access Token 없이 보냅니다. 만료된 토큰이 붙어 있어도 무시됩니다.
- 401의 `type`으로 다음 행동을 정합니다: `token-expired`는 재발급, `invalid-token`·`unauthorized`는 재로그인.
- 503 `auth-key-unavailable`은 토큰 문제가 아니므로 재발급도 재로그인도 하지 않고, 토큰을 그대로 두고 잠시 후 다시 보냅니다.

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
- MySQL(`TestcontainersConfiguration`), Redis(`RedisTestcontainersConfiguration`), Kafka(`KafkaTestcontainersConfiguration`) 컨테이너는 설정이 하나씩 따로 있고, `AbstractIntegrationTest`가 셋을 함께 켭니다. 통합 테스트는 `AbstractIntegrationTest`를 상속하기만 하면 Redis와 Kafka를 쓸 수 있습니다. MySQL과 같이 JVM당 하나를 공유하고, 버전은 `REDIS_VERSION`(기본 `8.10`)을 따릅니다. 기동 시간 때문에 **단일 노드**로 띄우며(접속 정보 빈이 `cluster.nodes` 속성보다 우선), 클러스터 모드 접속은 `common`의 `RedisClusterConnectionTest`(클러스터 모드 컨테이너 1개)가 따로 검증합니다. 장애 조치는 자동 테스트가 없고 위 로컬 Redis 클러스터 절의 절차로 확인합니다. 슬라이스 테스트(`@DataRedisTest`)만 `@Import(RedisTestcontainersConfiguration.class)`로 직접 켭니다. 예시는 `common`의 `RedisConnectionTest`입니다.
- Redis 연결 확인: Redis를 켜고(`docker compose up -d`) `./gradlew :monolith:bootRun`으로 실행한 뒤 `curl localhost:18080/actuator/health`가 `UP`이면 Redis까지 연결된 것입니다. Redis에 연결하지 못하면 503(`DOWN`)이 됩니다. 통합 테스트는 Redis 컨테이너가 함께 떠서 `UP`입니다.
- 통합 테스트는 Kafka도 Testcontainers로 띄웁니다. MySQL처럼 서브프로젝트의 테스트 실행(JVM) 한 번에 브로커 1대가 뜨고 모든 테스트 컨텍스트가 같이 씁니다(`KafkaTestcontainersConfiguration`). 버전은 compose와 같은 `KAFKA_VERSION`을 따르고, 없으면 `4.2.1`입니다.
- 슬라이스 테스트는 필요한 설정만 `@Import`합니다. Kafka만 필요하면 `@Import(KafkaTestcontainersConfiguration.class)`입니다(예시는 `common`의 `TestKafkaConsumerTest`). `@DataJpaTest`의 `TestcontainersConfiguration`은 이제 MySQL만 켭니다.
- 보낸 메시지의 JSON을 그대로 확인하려면 `@Import(TestKafkaConsumer.class)`로 테스트용 컨슈머를 켭니다. `test-topic`의 메시지를 문자열로 모아 `getReceivedMessage(timeout, unit)`로 꺼내고, 토픽은 테스트가 만들며, 파티션을 받은 뒤(`isAssigned()`)에 보내야 받습니다. 위 두 클래스에 `@TestConfiguration`·`@TestComponent`를 붙이지 않은 이유는 `AbstractIntegrationTest`가 `com.ok`를 스캔해서 붙이면 모든 통합 테스트에 켜지기 때문입니다.
- 토픽의 메시지는 테스트마다 지워지지 않습니다. 메시지를 확인하는 테스트는 자기가 만든 키(예: `payoutId`)로 걸러서 셉니다.
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

## 11. 비동기 처리 (이벤트 리스너와 `@Async`)

모듈 간 이벤트를 받는 `@ApplicationModuleListener`와 `@Async`는 요청 스레드가 아니라 별도 실행기의 스레드에서 실행됩니다. 이 구조는 모든 비동기 작업이 Boot 기본 실행기 하나를 공유하던 것을 용도별 전용 실행기로 나눕니다. 실행기는 이름을 붙여 여러 개 둘 수 있고, 동시 실행 상한 같은 값은 코드가 아니라 yml과 환경변수로 바꿉니다. 구현은 `common`의 [`AsyncConfig`](../common/src/main/java/com/ok/common/config/AsyncConfig.java), [`AsyncExecutorFactory`](../common/src/main/java/com/ok/common/config/AsyncExecutorFactory.java), [`AsyncProperties`](../common/src/main/java/com/ok/common/config/AsyncProperties.java)에 있습니다.

### 기본 동작

- Spring Modulith가 `@EnableAsync`를 켜 주므로 리스너는 원래 비동기입니다. 이름 없는 `@Async`가 쓰는 Boot 기본 실행기(`applicationTaskExecutor`)는 코어 8, 대기열 무제한이고 모든 비동기 작업이 공유합니다. 그래서 느린 작업이 다른 작업을 지연시킬 수 있고, 밀린 작업은 대기열에 메모리로 쌓입니다. 아래 구조는 이 공유 풀을 용도별 실행기로 나눕니다.
- 이름을 지정하지 않은 `@Async`와 `@ApplicationModuleListener`는 `app.async.default-executor`(기본 `event`)의 실행기로 갑니다. 기존 리스너 코드는 바꿀 필요가 없습니다. `@ApplicationModuleListener`는 실행기를 지정할 수 없어서 모든 리스너가 이 기본 실행기 하나를 씁니다.
- 실행기는 두 종류입니다. **`platform`**(`event`의 기본)은 정해진 수의 일반 스레드와 대기열을 씁니다. 대기열이 차기 전에는 작업을 넘기는 스레드가 기다리지 않아서 리스너가 요청 스레드를 막지 않습니다. **`virtual`**은 작업마다 가상 스레드를 새로 만듭니다(풀도 대기열도 없습니다). 부족한 자원인 DB 커넥션을 기준으로 **동시 실행 상한**(`concurrency-limit`)을 두고, 상한이 차면 작업을 넘기는 스레드가 기다립니다.
- 두 종류 모두 가득 찼을 때 작업을 거절하거나 버리지 않고 넘기는 스레드가 자리가 날 때까지 기다립니다. 거절하면 커밋 뒤 콜백에서 호출한 쪽으로 예외가 올라가 이미 커밋된 요청이 오류가 되고, 같은 커밋의 다른 리스너가 실행되지 않는 것을 확인했기 때문입니다.
- `spring.threads.virtual.enabled`는 쓰지 않습니다. 이 설정은 Tomcat 요청 스레드와 `@Scheduled`까지 바꾸는 전역 설정이라, `virtual` 실행기에만 가상 스레드를 적용합니다.
- 실행기를 직접 등록하므로 Boot의 `spring.task.execution.*` 설정은 적용되지 않습니다.

### 설정 키

`app.async.default-executor`는 이름 없는 `@Async`가 쓸 실행기 이름이고, `app.async.executors.<이름>`에 실행기마다 아래 값을 적습니다. 생략한 값은 기본값을 씁니다. 값이 잘못되면(상한 0 이하 등) 앱이 시작되지 않습니다. `type`에 따라 쓰는 값이 다르고, 쓰지 않는 값은 무시합니다.

| 키 | 기본값 | 설명 |
|---|---|---|
| `type` | `virtual` (`event`는 `platform`) | `platform`은 일반 스레드 풀과 대기열, `virtual`은 작업마다 가상 스레드입니다. 요청 스레드가 막히면 안 되는 곳(이벤트 리스너 등)은 대기열이 있는 `platform`을 씁니다. 가상 스레드에 문제가 생기면 재빌드 없이 `platform`으로 되돌릴 수 있습니다 |
| `concurrency-limit` | `5` | `virtual`에서 동시에 실행할 작업 수의 상한. 상한이 차면 작업을 넘기는 스레드가 기다립니다 |
| `pool-size` | `8` | `platform`의 스레드 수. 대기열이 차기 전에는 스레드가 늘지 않아 코어와 최대를 같게 씁니다 |
| `queue-capacity` | `1000` | `platform`의 대기열 크기. 가득 차면 작업을 넘기는 스레드가 기다립니다. 1000은 측정 근거 없는 임시값입니다 |
| `task-termination-timeout` | `10s` | 앱을 종료할 때 실행 중인 작업이 끝나기를 기다리는 시간. 끝나지 못한 이벤트는 미완료로 남아 재시작 때 재발행됩니다(`MODULITH_REPUBLISH_ON_RESTART`, 운영은 `true`) |
| `thread-name-prefix` | `<이름>-` | 스레드 이름의 앞부분. 로그에서 어느 실행기의 스레드인지 구분합니다 |

`event` 실행기는 설정이 없어도 기본값으로 항상 있고, 값은 `common-async.yaml`에 있습니다.

### 값 바꾸기

- 값은 앱이 시작할 때 한 번 읽습니다. 바꾸면 **재시작**해야 하고, 재빌드는 필요 없습니다.
- `event` 값은 환경변수로 바꿉니다: `ASYNC_EVENT_POOL_SIZE`, `ASYNC_EVENT_QUEUE_CAPACITY`, `ASYNC_EVENT_TERMINATION_TIMEOUT`.
- **앱 yml(`application.yaml` 등)에 `event` 값을 써도 적용되지 않습니다.** `spring.config.import`로 가져온 파일의 값이 가져온 앱 yml의 값보다 우선하기 때문입니다. `event` 값은 환경변수로 바꾸거나 `common-async.yaml`을 고칩니다.
- 새 이름의 실행기 항목은 앱 yml에 추가할 수 있습니다. 이름이 다른 항목은 서로 합쳐지고, 같은 이름의 항목은 필드 단위로 합쳐집니다.
- 새 이름 항목에서 환경변수로 바꿀 값은 yml에 `${ORDER_EXTERNAL_API_CONCURRENCY_LIMIT:3}`처럼 자리표시자를 직접 적습니다. 키 이름의 `-`는 환경변수 이름으로 표현할 수 없어서, 자리표시자 없이는 일부 값만 환경변수로 덮어쓰기가 어렵습니다.

### 모듈에서 실행기 추가하기

리스너가 아닌 외부 호출처럼 `event`와 따로 상한을 두고 싶은 작업이 있을 때만 추가합니다. 이름은 모듈 이름을 앞에 붙입니다(`order-external-api`). `monolith`는 빈 이름 공간이 하나라 이름이 겹치면 앱이 시작되지 않습니다.

1. 모듈의 `config` 패키지에서 실행기를 한 줄로 선언합니다. 빈 이름, yml 키, `@Async` 이름을 같은 상수로 맞춥니다.

    ```java
    @Configuration
    public class OrderAsyncConfig {

        public static final String EXTERNAL_API_EXECUTOR = "order-external-api";

        @Bean(EXTERNAL_API_EXECUTOR)
        AsyncTaskExecutor orderExternalApiExecutor(AsyncExecutorFactory factory) {
            return factory.create(EXTERNAL_API_EXECUTOR);
        }
    }
    ```

2. yml에 같은 이름의 항목을 추가합니다. 항목이 없으면 앱이 시작할 때 실패합니다(`app.async.executors.order-external-api 설정이 없습니다`).

    ```yaml
    app:
      async:
        executors:
          order-external-api:
            concurrency-limit: ${ORDER_EXTERNAL_API_CONCURRENCY_LIMIT:3}
            thread-name-prefix: order-api-
    ```

    `monolith`는 `application-<모듈>.yaml`을 읽지 않으므로, 모듈 yaml과 `monolith`의 `application.yaml`에 같은 항목을 둡니다. 한쪽에 빠지면 그 앱이 시작할 때 실패해서 바로 드러납니다.

3. 다른 빈에서 부르는 메서드에 이름을 지정합니다.

    ```java
    @Async(OrderAsyncConfig.EXTERNAL_API_EXECUTOR)
    public void notifyShipped(Long orderId) { ... }
    ```

### 주의할 점

- **DB 커넥션은 실행기가 나눠 씁니다.** `pool-size`와 `concurrency-limit`은 실행기마다 따로 적용되지만 커넥션 풀(Hikari 기본 10)은 앱 전체가 공유합니다. DB를 쓰는 실행기들의 스레드 수·상한 합계가 풀 크기보다 작아야 HTTP 요청이 커넥션을 얻지 못하는 일이 없습니다. 이 합계는 앱이 검사하지 않으니 실행기를 추가할 때 직접 확인합니다.
- **모든 리스너는 `event` 실행기 하나를 공유합니다.** `@ApplicationModuleListener`는 실행기를 지정할 수 없어서, 느린 외부 호출을 하는 리스너가 스레드를 다 차지하면 다른 리스너가 대기열에서 기다릴 수 있습니다. 리스너끼리의 격리는 아직 없습니다.
- **가득 차면 작업을 넘긴 스레드가 기다립니다.** `platform`은 대기열이 다 찰 때만, `virtual`은 상한이 차면 바로 기다립니다. 요청 스레드가 직접 부르는 `@Async`는 대기열이 있는 `platform`이 안전합니다. 작업을 거절하는 옵션은 두지 않았습니다. 거절하면 커밋 뒤 콜백에서 호출한 쪽으로 예외가 올라가 이미 커밋된 요청이 오류가 되고, 같은 커밋의 다른 리스너가 실행되지 않는 것을 확인했기 때문입니다.
- **`@Async` 이름 오타는 처음 호출될 때 실패합니다.** `@Async("이름")`에 문자열을 직접 쓰지 않고 위처럼 상수를 씁니다.
- **같은 클래스 안에서 `@Async` 메서드를 부르면 비동기가 되지 않습니다.** 프록시를 거치지 않아 오류 없이 동기로 실행됩니다. 다른 빈에서 호출합니다.
- **리스너는 처리 순서가 보장되지 않고, 같은 이벤트를 두 번 받을 수 있습니다.** 재시작 때 미완료 이벤트가 재발행되기 때문에 같은 이벤트를 여러 번 처리해도 결과가 같아야 합니다.
- **비동기 작업의 예외는 호출한 쪽으로 전달되지 않습니다.** `[ASYNC_LISTENER_FAILED]` 로그로 남고, 이벤트는 미완료로 남습니다.
- **MDC는 있는 값을 그대로 복사합니다**(`MdcTaskDecorator`). traceId는 [12. 추적](#12-추적-traceid)의 필터·스케줄러·배치가 MDC에 넣고, orderId 같은 다른 추적 키도 함께 복사됩니다. 로그 패턴에는 아직 찍히지 않고, 재발행이나 Kafka 경계에서는 전달되지 않습니다. `SecurityContext`는 전달하지 않습니다.

## 12. 추적 (traceId)

로그 한 줄이 어느 요청이나 실행에서 나왔는지 잇기 위해, 진입점마다 traceId를 정해 로그의 MDC(`traceId` 키)에 넣습니다. 구현은 `common`의 [`com.ok.common.trace`](../common/src/main/java/com/ok/common/trace) 패키지이고, 전체 설계는 [`design/order-flow-tracking.md`](design/order-flow-tracking.md)에 있습니다. 이 단계는 traceId를 발급해 MDC에 넣는 데까지이고, 로그 패턴과 JSON 출력은 포함하지 않습니다.

### 진입점별 동작

| 진입점 | traceId |
|---|---|
| HTTP | 서버끼리 부르는 `/internal/` 요청(loopback에서 온 것)에서만 요청 헤더 `X-Trace-Id`가 **0으로 시작하지 않는 숫자 15~18자리**면 이어받고, 아니면(문자, 14자리 이하, 19자리 이상, 빈 값) 무시하고 새로 발급합니다. 공개 API는 헤더를 보내도 항상 새로 발급합니다(외부 호출자가 기존 traceId로 이력을 겹치게 하지 못하도록). `/internal/` 호출이 보낸 값의 형식이 틀리면 WARN 로그가 남고 새로 발급합니다(값은 찍지 않고 길이만 남깁니다). 발급한 값이든 이어받은 값이든 응답 헤더 `X-Trace-Id`로 돌려줍니다. 401·403 응답에도 실립니다 |
| `@Scheduled` | 실행할 때마다 새로 발급합니다. 끝나면 실행한 스레드의 MDC를 이전 값으로 되돌립니다 |
| Spring Batch | Job 실행마다 새로 발급합니다. Job은 호출한 스레드에서 동기로 실행되므로, 끝나면 호출한 쪽의 MDC(예: HTTP 요청의 traceId)로 되돌립니다. Batch가 없는 모듈에서는 등록되지 않습니다 |

### 경계 넘어 전파

| 경계 | 방식 |
|---|---|
| 비동기 리스너(`@ApplicationModuleListener`) | `MdcTaskDecorator`가 MDC 전체를 복사합니다 |
| 내부 HTTP | 내부 API 클라이언트(`InternalRestClient`, 상품의 `OrderApiClient`)가 `TraceHeaderInterceptor`로 MDC에 있는 `X-Trace-Id`, `X-Order-Id`, `X-Shipment-Id`만 보냅니다 |
| `/internal/` 수신 | `TraceFilter`가 헤더로 복원합니다. `X-Order-Id`·`X-Shipment-Id`는 `/internal/` 경로에서, loopback 주소에서 온 요청이고 0으로 시작하지 않는 숫자 1~18자리일 때만 받습니다 |

새 내부 HTTP 클라이언트를 만들면 `RestClient`에 `TraceHeaderInterceptor`를 붙입니다. Kafka로 넘기는 것은 후속 이슈입니다(#382).

### traceId 해석

traceId는 10진수 long이고, 비트 구성은 다음과 같습니다.

| 비트 | 내용 |
|---|---|
| 상위 41 | 발급 시각. 2026-01-01T00:00:00Z(`1767225600000`)부터 지난 ms |
| 다음 10 | 노드 번호(`trace.node-id`) |
| 하위 12 | 같은 ms 안의 순번(0~4095). 다 쓰면 다음 ms까지 기다립니다 |

값이 클수록 나중에 발급된 것입니다. 발급 시각은 값에서 역산합니다.

```sql
-- 예: traceId 111634463317819397 -> 2026-11-05 10:15:30.123 (KST), 노드 0, 순번 5
SELECT FROM_UNIXTIME(((111634463317819397 >> 22) + 1767225600000) / 1000) AS issued_at,
       (111634463317819397 >> 12) & 1023 AS node_id,
       111634463317819397 & 4095 AS sequence;
```

- 역산한 시각은 절대 시각이고, `FROM_UNIXTIME`은 DB 세션 시간대로 보여 줍니다. 위 예의 KST는 세션이 `Asia/Seoul`일 때입니다.
- 서버 시계가 뒤로 가면(NTP 보정 등) 예외 없이 마지막 발급 시각을 유지하며 계속 발급합니다. 이때 값의 시각이 실제와 조금 다를 수 있습니다.
- 값이 2033년 중반부터 19자리가 됩니다. 요청 헤더로 이어받는 규칙은 18자리까지라서, 그 이후 발급된 값은 `X-Trace-Id`로 보내도 새로 발급됩니다.
- 서버를 둘 이상 띄우면 `trace.node-id`를 서버마다 다르게 정합니다(예: 환경변수 `TRACE_NODE_ID=1`). 같은 값이면 서로 다른 서버가 같은 ms에 만든 traceId가 겹칠 수 있습니다. 지금은 인스턴스가 하나라 기본값 0을 씁니다. 설정하지 않으면 기동할 때 경고 로그가 남습니다.
- `@Scheduled` 추적은 `ObservationRegistry`로 동작합니다. `management.observations`로 관찰을 끄면 스케줄러 로그에 traceId가 없을 수 있습니다.

### 코드에서 쓰기

- 추적 키(`traceId`, `orderId`, `shipmentId`, `module`, `step`, `eventType`)의 MDC 이름은 [`TraceContext`](../common/src/main/java/com/ok/common/trace/TraceContext.java)의 상수입니다. 현재 진입점이 채우는 것은 `traceId`뿐이고(`/internal/` 요청은 헤더가 있으면 `orderId`, `shipmentId`도), 나머지는 `bindOrderId`, `bindShipmentId`로 범위 안에서 붙일 수 있습니다.
- 새 진입점이 직접 범위를 열어야 하면 `try (TraceContext.Scope scope = TraceContext.open(traceId)) { ... }`로 씁니다. 닫으면 진입 전 MDC 전체로 되돌립니다.

## 13. Redis 캐시 (`CacheStore`, #538)

Redis는 `CacheStore` 인터페이스로만 쓴다. 클라이언트는 Lettuce(Spring Data Redis 기본)이고, Redis에 닿는 코드는 [`com.ok.common.persistence.redis`](../common/src/main/java/com/ok/common/persistence/redis) 한 곳에 있다. 서비스는 `RedisTemplate`, `StringRedisTemplate`, Lettuce 타입을 직접 쓰지 않는다(monolith의 `ArchitectureTest`가 막는다). 나중에 클라이언트나 저장소가 바뀌어도 이 패키지의 구현체만 바뀐다.

```java
private final CacheStore cacheStore;

cacheStore.put("delivery:shipping-fee:63100", fee, Duration.ofMinutes(10));    // 저장 (TTL 필수)
Optional<ShippingFee> cached = cacheStore.get("delivery:shipping-fee:63100", ShippingFee.class);
cacheStore.evict("delivery:shipping-fee:63100");                                // 지우기
```

- **키**는 `모듈:도메인:식별자` 형식으로 호출하는 쪽이 만든다. 다른 모듈과 겹치지 않게 모듈 이름을 앞에 둔다.
- **TTL은 필수**이고 1밀리초 이상이다. 운영 Redis는 메모리가 차도 키를 지우지 않고 쓰기를 거절(`noeviction`)하므로, 만료 없는 키가 쌓이면 모든 쓰기가 막힌다.
- **값은 JSON 문자열**로 저장하고 타입 정보(클래스 이름 등)는 싣지 않는다. 읽는 쪽이 `get(key, 타입)`으로 타입을 정한다(Kafka 메시지와 같은 방식). 직렬화는 앱의 `JsonMapper`를 쓰고 JDK 직렬화는 쓰지 않는다.
- 저장된 값을 읽지 못하면(배포로 클래스 모양이 바뀐 경우 등) 오류 대신 **캐시 미스**로 돌려주고 그 키를 지운다. 오류로 두면 TTL이 끝날 때까지 같은 키가 계속 실패한다.
- **Redis 장애 시**: 캐시는 없어도 본 흐름이 동작해야 하므로, `get`은 캐시 미스(`Optional.empty()`)로 돌려주고 `put`은 경고 로그만 남기고 끝난다. 호출하는 쪽은 `get`이 비면 원본(DB 등)에서 읽으면 된다. `evict`만 예외(`DataAccessException`)를 그대로 올린다. 삼키면 낡은 값이 TTL이 끝날 때까지 남는 것을 알 수 없어서, 어떻게 할지(트랜잭션 실패, 짧은 TTL에 맡김 등)를 호출하는 쪽이 정한다. Redis가 응답하지 않으면 `common-redis.yaml`의 타임아웃(연결 2초, 명령 1초)만큼 요청이 느려진다.
- `List<X>`처럼 제네릭 타입을 바로 읽는 메서드는 아직 없다. 필요한 사용처가 생기면 그때 더한다.
- 테스트는 인터페이스의 Fake가 아직 없다. Redis를 쓰는 통합 테스트는 `AbstractIntegrationTest`가 띄우는 Redis 컨테이너를 쓴다.
