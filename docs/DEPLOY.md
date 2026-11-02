# 배포 가이드

EC2 한 대에 nginx·앱·MySQL·Kafka를 Docker 컨테이너로 띄워 수동으로 배포하는 방법입니다.
서버(EC2) 자체를 만드는 방법은 [`INFRA.md`](INFRA.md)를 보세요.

## 1. 구성

```
인터넷 ──80──▶ nginx ──/api/v1/**──▶ app ──▶ mysql
                 └─ 그 외 경로(/internal, /actuator 등) → 404
                                       └──▶ kafka-1, kafka-2, kafka-3 (클러스터 1개)
```

- 외부에는 `/api/v1/**`만 공개합니다. 인증·인가는 앱(JWT)이 처리합니다.
- `/internal/**`은 모듈 간 내부 호출용이라 인증이 없습니다. nginx가 404로 막고, 앱도 같은 컨테이너(loopback)에서 온 요청만 받습니다.
  - 그래서 내부 API 클라이언트의 `app.internal-api.base-url`은 기본값(`http://localhost:8080`)을 유지해야 합니다. nginx 주소로 바꾸면 내부 호출이 막힙니다.
- app·mysql·kafka는 호스트 포트를 열지 않습니다. Kafka는 compose 네트워크 안에서만 닿고(보안 그룹에도 규칙 없음), 인증 없는 PLAINTEXT로 통신합니다.
  - Kafka 3대는 별도 네트워크(`kafka-net`)에 있고, app만 이 네트워크와 기본 네트워크(mysql·nginx)에 함께 붙습니다. mysql·nginx는 Kafka에 닿지 않습니다.
- 파일: 레포의 `deploy/` → 서버의 `/opt/paldo-gotgan`
- 주소: `http://2000k.duckdns.org` (DuckDNS 무료 도메인, EIP를 가리킨다)
- 이미지: GHCR `ghcr.io/prgrms-be-adv-devcourse/paldo-gotgan` (비공개 패키지). 서버는 pull 전용 토큰으로 로그인해 받습니다.
- 앱은 1개만 띄웁니다. 배포 중 수십 초간 요청이 실패합니다. 여러 개를 띄우려면 정산 스케줄러 중복 실행 문제를 먼저 해결해야 합니다.

### Kafka (#318)

- 클러스터 1개에 브로커 3대(`kafka-1`~`kafka-3`), 브로커와 컨트롤러를 한 프로세스에서 돌리는 KRaft combined 모드입니다. 이미지는 `apache/kafka:${KAFKA_VERSION}`입니다.
- 메시지는 3대에 복사되고 2대 이상에 저장돼야 발행 성공입니다(`min.insync.replicas=2`, 앱은 `acks=all`). 그래서 **브로커 1대 고장은 버티지만, 같은 EC2라 EC2가 죽으면 3대가 함께 죽고 처리량도 늘지 않습니다.** 장애 대비용 구성입니다.
- app은 mysql과 Kafka 3대가 모두 healthy가 된 뒤 시작합니다. 브로커가 healthy가 되지 못하면 app이 뜨지 않고 배포가 실패합니다.
- 데이터는 루트 디스크의 도커 볼륨(`kafka-N-data`)에 둡니다. `/data`(EBS)는 MySQL 전용이라 Kafka가 디스크를 채워도 MySQL은 멈추지 않습니다.
  - 파티션 하나당 256MB(`KAFKA_LOG_RETENTION_BYTES`)를 넘으면 오래된 메시지부터 지웁니다. 아직 읽히지 않은 메시지도 지워질 수 있습니다. 토픽이 늘면 `토픽 수 × 파티션 수 × 3벌 × 256MB`가 루트 디스크 여유(30GB 중 OS·도커 이미지 사용분 제외) 안에 드는지 다시 계산합니다.
  - 인스턴스를 교체하면 루트 디스크와 함께 Kafka 데이터도 사라지고 새 클러스터로 시작합니다. 원본 데이터는 DB에 있습니다.
- **`KAFKA_CLUSTER_ID`는 처음 정한 뒤 바꾸지 않습니다.** 바꾸면 기존 볼륨과 맞지 않아 브로커가 뜨지 않습니다. 꼭 바꿔야 하면 `docker compose -f compose.prod.yml down`, `docker volume rm paldo-gotgan_kafka-1-data paldo-gotgan_kafka-2-data paldo-gotgan_kafka-3-data`로 볼륨까지 지운 뒤 배포합니다(Kafka 메시지가 모두 사라집니다).
- Kafka 상태는 앱 헬스체크(`/actuator/health`)에 들어가지 않습니다. `docker compose ps`의 kafka 컨테이너 상태로 봅니다.

**메모리 예산 (t3.large 8GiB, 추정치)**

| 구성 | 예산 |
|---|---|
| app | 2560m (`mem_limit`) |
| kafka 3대 | 대당 680m (`mem_limit`, 힙 384m), 합계 약 2GiB |
| mysql | 제한 없음, 약 0.5~1GiB |
| nginx, OS·Docker | 약 0.6GiB |
| 합계 | 약 6.2GiB (여유 약 1.8GiB) |

로컬에서 잰 브로커 사용량은 대기 상태 대당 약 290~350MiB입니다. 서버에서는 `free -m`, `docker stats --no-stream`으로 확인합니다.

## 2. 최초 설정 (서버당 1회)

> **참고:** 공개 API(로그인·회원가입, 상품 조회 등)를 뺀 `/api/v1` 요청은 로그인이 필요합니다(#242).

먼저 [3. 배포](#3-배포)처럼 GHCR에 이미지가 올라가 있는지 확인합니다. 이미지가 없으면 아래 마지막 `./deploy.sh`가 pull에 실패합니다.

서버가 비공개 패키지를 받으려면 pull 전용 토큰이 필요합니다. GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)에서 만듭니다.

- 권한은 `read:packages`만 줍니다. push용 토큰을 재사용하지 않습니다. classic 토큰은 계정이 접근할 수 있는 모든 패키지를 읽을 수 있어서 권한을 최소로 둡니다.
- 만료일을 지정하고 따로 적어 둡니다. 만료되면 `./deploy.sh`가 pull 단계에서 실패합니다.
- 조직이 SSO를 쓰면 발급 후 Configure SSO에서 조직을 승인합니다.

```bash
# 로컬: 배포 파일 복사
scp -i <키 파일> -r deploy/compose.prod.yml deploy/deploy.sh deploy/kafka-rollout.sh deploy/nginx deploy/initdb deploy/.env.prod.example ubuntu@<EIP>:/tmp/

# 서버
ssh -i <키 파일> ubuntu@<EIP>
docker login ghcr.io -u <GitHub 사용자>   # Password: 에 pull 전용 토큰 (화면에 표시되지 않음)
chmod 600 ~/.docker/config.json             # 토큰이 base64로만 저장되므로 다른 사용자가 읽지 못하게 한다
sudo mkdir -p /opt/paldo-gotgan && sudo chown ubuntu:ubuntu /opt/paldo-gotgan
mv /tmp/compose.prod.yml /tmp/deploy.sh /tmp/kafka-rollout.sh /tmp/nginx /tmp/initdb /tmp/.env.prod.example /opt/paldo-gotgan/
cd /opt/paldo-gotgan
chmod +x deploy.sh kafka-rollout.sh
cp .env.prod.example .env && chmod 600 .env
docker run --rm apache/kafka:4.2.1 /opt/kafka/bin/kafka-storage.sh random-uuid   # KAFKA_CLUSTER_ID에 넣을 값
vi .env                      # 비밀번호·키, KAFKA_CLUSTER_ID 채우기
mountpoint /data             # "/data is a mountpoint"가 나와야 한다
# JWT 서명 키 만들기 (아래 9절)
./deploy.sh
```

## 3. 배포

이미지는 배포 브랜치 `semi`에 push되면 GitHub Actions(`.github/workflows/docker-image.yml`)가 빌드해 GHCR에 `latest`와 커밋 SHA 7자리 태그로 올립니다.

- `semi`는 세미 프로젝트까지 쓰는 임시 배포 브랜치입니다. dev 머지마다 빌드하면 너무 자주 돌아서, 확인하고 싶을 때만 `semi`를 올립니다. 이후 main으로 옮길 예정입니다.
- **모듈별 DB 스키마 이전(#288)이 들어간 버전을 처음 배포할 때는 배포 전에 [8. 모듈별 DB 스키마 이전](#8-모듈별-db-스키마-이전-288-한-번만)을 먼저 실행합니다.** 하지 않으면 새 버전 앱이 기동 검증에서 멈춥니다.
- `semi`에는 직접 커밋하거나 PR을 머지하지 않고 dev를 그대로 따라가게만(fast-forward) 합니다. dev에 없는 코드가 배포되면, 나중에 dev에 들어오는 더 이른 번호의 마이그레이션 때문에 Flyway 검증이 실패해 앱이 뜨지 않습니다.

1. 배포할 시점의 dev로 `semi`를 올립니다. fast-forward가 안 되면 push가 거부됩니다.
   ```bash
   git fetch origin && git push origin origin/dev:semi
   ```
2. Actions 탭에서 `Docker Image` 실행이 성공했는지 확인합니다.
3. 서버에서 배포합니다.

```bash
cd /opt/paldo-gotgan && ./deploy.sh
```

- 패키지는 비공개로 둡니다. 서버는 [2. 최초 설정](#2-최초-설정-서버당-1회)에서 로그인한 pull 전용 토큰으로 받습니다.
- 서버는 `latest`를 받고, SHA 태그는 롤백에 씁니다. 빌드는 한 번에 하나씩 실행되고 대기 중인 실행은 새 실행이 오면 취소되므로, `semi`를 연달아 올리면 중간 커밋의 SHA 태그는 없을 수 있습니다. 롤백할 SHA는 GHCR 패키지 화면에서 실제 있는 태그를 확인합니다.
- 빌드만 다시 하려면 Actions 탭에서 `Docker Image` → Run workflow로 실행합니다. `semi`가 아닌 브랜치를 고르면 빌드는 건너뜁니다.
- 패키지에 Actions 쓰기 권한이 있어야 합니다. GHCR 패키지 설정 → Manage Actions access에서 이 레포를 Write로 추가합니다. 없으면 push가 `denied`로 실패합니다.
- `deploy.sh`는 app(과 mysql)을 먼저 올려 healthy를 확인한 뒤 nginx까지 올리고 `배포 성공`을 출력합니다. app 기동에 실패하거나 2분 안에 healthy가 되지 않으면 앱 로그를 출력하고 실패합니다.
- app보다 먼저 `kafka-rollout.sh`가 Kafka를 처리합니다. 설정이 바뀐 서비스를 compose가 동시에 다시 만들면 3대가 함께 내려가 Kafka가 멈추기 때문에, 상태에 따라 나눠 처리합니다.

| Kafka 상태 | 처리 |
|---|---|
| 떠 있는 브로커가 없음 (첫 배포, `down` 뒤) | 3대를 동시에 띄웁니다. 3대 중 2대가 모여야 시작하므로 1대씩 띄우면 끝없이 기다립니다 |
| 3대 모두 healthy | 1대씩 다시 띄우고, healthy와 복사 따라잡기(복사 덜 된 파티션 0개)를 기다린 뒤 다음 브로커로 갑니다. 설정·버전이 그대로면 다시 만들지 않아 바로 지나갑니다 |
| 켜지는 중(starting)인 브로커가 있음 (서버 재부팅 직후 등) | healthy가 될 때까지 기다린 뒤 위·아래 기준으로 판정합니다 |
| 일부만 살아 있음 | 배포를 중단합니다. 이 상태에서 1대를 더 내리면 클러스터가 멈추기 때문입니다. `docker compose -f compose.prod.yml ps`, `logs kafka-N`으로 원인을 보고 브로커를 살린 뒤 다시 배포합니다 |

- `KAFKA_VERSION`을 올릴 때도 `.env`만 바꾸고 `./deploy.sh`를 실행하면 위 순서로 1대씩 바뀝니다. 버전을 내리는 것은 Kafka가 보장하지 않으므로 하지 않습니다.
- **Kafka가 추가된 버전을 처음 배포하는 기존 서버**는 아래 순서로 준비합니다. `.env`에 `KAFKA_VERSION`, `KAFKA_CLUSTER_ID`가 없으면 compose가 `KAFKA_CLUSTER_ID가 필요하다`로 실패합니다.

```bash
# 로컬: 새 배포 파일 복사
scp -i <키 파일> deploy/compose.prod.yml deploy/deploy.sh deploy/kafka-rollout.sh ubuntu@<EIP>:/opt/paldo-gotgan/

# 서버
cd /opt/paldo-gotgan
chmod +x deploy.sh kafka-rollout.sh
echo "KAFKA_VERSION=4.2.1" >> .env
echo "KAFKA_CLUSTER_ID=$(docker run --rm apache/kafka:4.2.1 /opt/kafka/bin/kafka-storage.sh random-uuid)" >> .env
./deploy.sh
```

## 4. 롤백

```bash
cd /opt/paldo-gotgan
sed -i 's/^APP_IMAGE_TAG=.*/APP_IMAGE_TAG=<이전 SHA>/' .env
./deploy.sh
```

- 복구한 뒤 문제를 고쳐 다시 배포할 때 `APP_IMAGE_TAG=latest`로 되돌립니다.
- 이미 적용된 Flyway 마이그레이션은 이미지를 되돌려도 남습니다. 이전 버전과 호환되지 않는 마이그레이션(컬럼 삭제·이름 변경)은 배포 전에 확인합니다.

## 5. 확인 명령

```bash
docker compose -f compose.prod.yml ps                 # 상태 (app이 healthy인지)
docker compose -f compose.prod.yml logs -f app        # 앱 로그
docker compose -f compose.prod.yml exec mysql mysql -u root -p   # DB 접속
docker compose -f compose.prod.yml ps kafka-1 kafka-2 kafka-3    # Kafka 3대가 healthy인지
# Kafka 클러스터 상태(투표자 3대, 리더)와 복사가 덜 된 파티션(없어야 정상). CLI도 JVM을 띄우므로 힙을 작게 준다
docker compose -f compose.prod.yml exec kafka-1 sh -c 'KAFKA_HEAP_OPTS=-Xmx64m /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe --status'
docker compose -f compose.prod.yml exec kafka-1 sh -c 'KAFKA_HEAP_OPTS=-Xmx64m /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --under-replicated-partitions'
curl -s -o /dev/null -w '%{http_code}\n' localhost/api/v1/products/categories   # 200
curl -s -o /dev/null -w '%{http_code}\n' localhost/internal/v1/products/options # 404
```

## 6. 배포 파일 갱신

`deploy.sh`는 앱 이미지만 새로 받습니다(Kafka 이미지는 `KAFKA_VERSION`이 바뀌었을 때만 받습니다). `compose.prod.yml`, `deploy.sh`, `kafka-rollout.sh`나 `nginx/default.conf`가 바뀌면 아래 순서로 반영합니다.

```bash
# 로컬: 기존 파일 위에 덮어쓴다 (디렉터리를 mv로 옮기면 기존 디렉터리 안에 중첩되므로 목적지 경로를 직접 지정한다)
scp -i <키 파일> deploy/compose.prod.yml ubuntu@<EIP>:/opt/paldo-gotgan/compose.prod.yml
scp -i <키 파일> deploy/deploy.sh deploy/kafka-rollout.sh ubuntu@<EIP>:/opt/paldo-gotgan/
scp -i <키 파일> deploy/nginx/default.conf ubuntu@<EIP>:/opt/paldo-gotgan/nginx/default.conf

# 서버
cd /opt/paldo-gotgan
./deploy.sh
docker compose -f compose.prod.yml restart nginx
```

- Kafka 설정이 바뀐 `compose.prod.yml`도 `./deploy.sh`가 브로커를 1대씩 다시 띄웁니다. `docker compose up -d`를 직접 실행하면 3대가 동시에 다시 만들어지니 쓰지 않습니다.
- nginx는 설정 파일 하나를 bind mount합니다. 파일을 교체하면 inode가 바뀌어 실행 중인 컨테이너는 옛 파일을 계속 읽으므로, 반드시 nginx를 재시작합니다.

## 7. pull 토큰 교체

서버의 pull 전용 토큰이 만료되기 전에 새 토큰(`read:packages`)을 만들어 다시 로그인합니다. 같은 사용자로 로그인하면 기존 값을 덮어씁니다.

```bash
# 서버
docker login ghcr.io -u <GitHub 사용자>
docker pull ghcr.io/prgrms-be-adv-devcourse/paldo-gotgan:latest   # 새 토큰으로 받아지는지 확인
```

이전 토큰은 GitHub에서 삭제합니다.

## 8. 모듈별 DB 스키마 이전 (#288, 한 번만)

앱이 모듈마다 자기 MySQL 스키마(`paldogotgan_member`, `paldogotgan_wallet` 등 8개)를 쓰도록 바뀌었습니다. 기존 데이터가 있는 DB는 테이블을 새 스키마로 옮겨야 하고(데이터 복사 없이 `RENAME TABLE`), 이 버전을 처음 배포하기 전에 한 번만 실행합니다. 새 데이터 볼륨(최초 설치)은 MySQL 컨테이너가 스키마를 만들고 앱이 테이블을 만들므로 이 절차가 필요 없습니다.

- 이전하지 않은 DB로 새 버전을 띄우면 앱이 Flyway 검증(`Detected applied migration not resolved locally`)에서 멈춥니다. 빈 테이블이 만들어지지는 않습니다.
- 반대로, 이전한 DB에 **이전 버전 이미지를 띄우면** 기본 스키마에 빈 테이블이 새로 생기고 데이터가 갈라질 수 있어서, 이전 스크립트가 기본 스키마에 자리표시 테이블 `media`를 둡니다. 이전 버전 앱은 첫 마이그레이션(`media` 테이블 생성)에서 `already exists`로 멈춥니다. 되돌릴 때는 진짜 `media` 테이블이 돌아오는 것과 같은 한 문장(원자적)에서 이 테이블이 모듈 스키마로 함께 옮겨지므로, 되돌리기가 실패하면 자리표시도 그대로 남습니다. 이 자리표시는 **이전 스크립트로 이전한 DB에만** 생깁니다. 새 데이터 볼륨에서 새 버전으로 시작한 DB에는 없으므로, 그 DB에서는 이전 버전 이미지로 되돌리지 않습니다(기본 스키마에 빈 테이블이 생겨 데이터가 갈라집니다).
- 스크립트: `infra/mysql/migration-288/` (`migrate.sh`가 점검 → 이전 → 사후 점검을 이어서 실행하고, 실패하면 바로 멈춥니다). 서버에서 실행하려면 이 폴더를 `scp`로 서버에 복사합니다.
- 이전 중에는 앱이 꺼져 있어야 합니다(쓰기 중단). 테이블을 옮기는 `RENAME TABLE`은 데이터를 복사하지 않아 데이터 크기와 무관하게 짧습니다. 다만 이전 전후의 행 수·`CHECKSUM TABLE` 비교는 테이블 전체를 읽으므로 데이터가 크면 오래 걸립니다([운영 적용 전에 확인할 것](#운영-적용-전에-확인할-것) 참고).

```bash
# 서버 (/opt/paldo-gotgan)
docker compose -f compose.prod.yml stop app nginx          # 쓰기 중단
# 백업: EBS 스냅숏을 1차 복구 수단으로 만들고, 덤프도 남긴다. 덤프에는 회원·주소·지갑 원장이 모두 들어 있으므로 본인만 읽게 만들고 확인 뒤 지운다
(umask 077; docker compose -f compose.prod.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --triggers --databases "$MYSQL_DATABASE"' > /data/backup-before-288.sql)
# 이전. root 비밀번호는 명령행·환경변수로 넘기지 않는다(컨테이너 안의 MYSQL_ROOT_PASSWORD를 쓴다)
export MYSQL_EXEC='docker compose -f compose.prod.yml exec -T mysql'
./migrate.sh all <MYSQL_DATABASE 값> <MYSQL_USER 값> /data/migrate-288-out
```

- `migrate.sh`는 먼저 사전 점검을 하고(테이블 목록이 예상과 같은지, 알려진 55개 외의 마이그레이션 이력이 없는지, 트리거·뷰·테이블 권한이 없는지, 앱 연결·열린 트랜잭션이 없는지, 대상 스키마가 비어 있는지), 하나라도 실패하면 아무것도 바꾸지 않고 멈춥니다. 병합되지 않은 브랜치의 마이그레이션이 이력에 있으면 중단되니 그 브랜치를 정리한 뒤 다시 실행합니다.
- 이전 전후로 모든 테이블의 행 수와 `CHECKSUM TABLE` 값을 결과 폴더(`before.txt`, `after.txt`)에 남기고 같은지 비교합니다. 다르면 실패로 끝납니다.
- 이전 뒤 새 버전을 배포하고(`./deploy.sh`) 확인합니다: health UP, 로그인, 상품 조회, 지갑 잔액, 정산 조회, 가입 후 지갑이 생기는지. 앱 로그에서 Flyway가 스키마 9곳을 검증하고 새로 적용한 마이그레이션이 0건인지 봅니다.
- 이전이 끝나면 덤프 파일(`/data/backup-before-288.sql`)은 배포가 안정된 것을 확인한 뒤 지웁니다.

### `migrate.sh` 명령과 환경변수

`migrate.sh <명령> <기본 스키마> <앱 계정> [결과 폴더]` 형식으로 실행하고, 실행 전에 앱을 멈추고 백업합니다.

| 명령 | 동작 |
|---|---|
| `preflight` | 사전 점검(`00_preflight.sql`)만 합니다. 아무것도 바꾸지 않습니다 |
| `all` | 사전 점검 → 이전 전 행 수·체크섬 기록 → 이전(`10_forward.sql`) → 사후 점검(`20_verify.sql`) → 이전 후 비교. 어느 단계든 실패하면 즉시 멈추고, 이전 중 실패하면 상태와 다음 할 일을 출력합니다 |
| `rollback` | 이전한 것을 되돌립니다(`90_rollback.sql`, `91_rollback_drop.sql`). 이전 후 새 마이그레이션이 적용되지 않았을 때만 됩니다. 이전 도중 실패한 상태에서도 다시 실행할 수 있습니다 |
| `cleanup` | 테이블을 옮기기 전에 이전이 실패했을 때 만들어 둔 빈 모듈 스키마와 이력 복사본을 지웁니다 |

| 환경변수 | 의미 |
|---|---|
| `MYSQL_EXEC` | mysql 컨테이너에서 명령을 실행하는 접두어(예: `docker compose -f compose.prod.yml exec -T mysql`, 로컬은 `docker compose -f infra/mysql/compose.yml exec -T mysql`). 있으면 컨테이너 안에서 root로 실행하고 컨테이너의 `MYSQL_ROOT_PASSWORD`를 씁니다. 없으면 이 컴퓨터의 mysql 클라이언트로 root에 접속하고 비밀번호는 `MYSQL_PWD`에서 읽습니다. 어느 쪽이든 비밀번호는 명령행에 나타나지 않습니다 |
| `SNAPSHOT_MODE` | `rows`이면 테이블마다 `CHECKSUM TABLE`을 하지 않고 행 수만 기록·비교합니다(기본 `full`). `CHECKSUM TABLE`은 테이블 전체를 읽어 데이터가 크면 오래 걸립니다. 이전 자체는 메타데이터 작업이라 크기와 무관합니다 |

결과 폴더(기본 `./out/<시각>`)에 각 단계의 출력과 이전 전후의 행 수·체크섬이 남습니다.

### 사전 점검이 실패했을 때

`00_preflight.sql`의 출력 한 줄이 점검 하나이고(`CHECK <이름> <PASS|FAIL> <상세>`), `FAIL`이 하나라도 있으면 `migrate.sh`가 중단합니다.

| 점검 | 실패하는 경우와 대응 |
|---|---|
| (a) 테이블 목록 | 기본 스키마의 테이블이 이전 대상 목록(`tables.txt`)과 다릅니다(`event_publication`, `flyway_schema_history` 제외) |
| (b) 이력 55개 | 이력이 기대하는 55개 마이그레이션(파일명 기준)과 다릅니다. 이전 후 검증과 `rollback`이 55행을 전제로 하므로 누락과 초과를 모두 막습니다. 초과는 병합되지 않은 브랜치의 마이그레이션이고, 누락은 로컬 DB에 일부만 적용된 경우입니다. 누락이면 마이그레이션을 이어 적용하지 말고 DB를 초기화(방법 B)합니다 |
| (c) 실패한 이력 | 실패한 마이그레이션 이력(`success = 0`)이 있습니다 |
| (d) 트리거·뷰·권한 | 트리거·뷰·테이블 단위 권한이 있습니다. 트리거가 있으면 `RENAME`이 실패하고, 테이블 권한은 `RENAME`을 따라가지 않습니다 |
| (e) 대상 스키마 | 대상 모듈 스키마에 테이블이 있습니다. 이미 이전했거나 앱이 먼저 떠서 빈 테이블을 만든 경우입니다 |
| (e-1) 이스케이프 없는 권한 | 앱 계정에 `paldogotgan_x` 형태(이스케이프 없음)의 권한이 있습니다. `_`가 와일드카드라 다른 DB까지 닿고 이전 스크립트의 `REVOKE`(`paldogotgan\_x`)가 거두지 못합니다. 있으면 ``REVOKE ... ON `paldogotgan_x`.*``로 먼저 거둡니다 |
| (e-2) 앱 정지 | 앱 계정 연결이나 열린 트랜잭션이 있습니다. 있으면 `RENAME`이 메타데이터 락을 기다립니다 |
| (f) 참고 | 아직 발행이 끝나지 않은 이벤트 수입니다(0이 좋고, 있어도 이전 후 재발행됩니다) |

### 이전이 도중에 실패했을 때

`migrate.sh all`은 이전(`10_forward.sql`) 중에 실패하면 현재 상태와 다음 할 일을 출력합니다. 앱은 올리지 않습니다.

| 실패한 곳 | 상태 | 할 일 |
|---|---|---|
| 사전 점검 | 아무것도 바뀌지 않음 | 실패한 점검(`preflight.log`)을 고치고 다시 실행 |
| 스키마 생성·이력 복사·`RENAME TABLE` (예: 열린 트랜잭션 때문에 락 대기 시간 초과) | 테이블은 그대로, 빈 모듈 스키마와 이력 복사본만 있음 | `./migrate.sh cleanup ...`으로 정리하고 원인을 고친 뒤 처음부터 다시 실행 |
| `RENAME TABLE` 뒤(기본 이력 정리, 자리표시 테이블, 사후 점검, 전후 비교) | 테이블은 옮겨졌고 일부 정리가 덜 됨(예: 기본 이력이 55행으로 남음) | `./migrate.sh rollback ...`로 되돌린 뒤 원인을 고쳐 다시 실행(되돌리기는 도중에 실패한 상태에서도 다시 실행할 수 있고, 끝나면 기본 이력이 55행인지 확인한다. 예외: `RENAME TABLE`은 성공했는데 이력 복원만 실패한 경우에는 모듈 스키마에 옮겨진 테이블이 0이라 `rollback`이 "이미 되돌렸다"며 멈추고, 안내하는 `cleanup`은 모듈 스키마와 이력 복사본을 지우므로 쓰지 않는다. 이때는 이력 복원이 필요하니 백업 복원 절차를 따른다) |

### 이전 되돌리기

새 앱을 멈춘 뒤, 이전 후 새 마이그레이션이 적용되기 전일 때만 스크립트로 되돌립니다. 새 앱이 쓴 데이터도 함께 원래 스키마로 돌아옵니다.

```bash
docker compose -f compose.prod.yml stop app nginx
./migrate.sh rollback <MYSQL_DATABASE 값> <MYSQL_USER 값> /data/migrate-288-out   # 같은 결과 폴더를 넘긴다
APP_IMAGE_TAG=<이전 SHA 태그> ./deploy.sh                                          # 이전 이미지로 배포
```

- 이전 버전 이미지는 **되돌리기를 먼저 한 뒤에** 배포합니다. 되돌리기 전에 이전 이미지를 띄우면 앞에서 설명한 대로 멈추는데, 이때 기본 스키마 이력에 실패한 마이그레이션 행이 남습니다. 그러면 `rollback`이 기본 스키마 이력에 실패한 행(`success = 0`)이 있다며 거부하므로, 안내대로 실패 행을 지운 뒤(`DELETE FROM <기본 스키마>.flyway_schema_history WHERE success = 0`) 다시 실행합니다.
- 되돌린 뒤 행 수·체크섬이 되돌리기 직전과 같은지 스크립트가 비교합니다. 이전 후 새 마이그레이션이 적용됐거나 스크립트가 실패하면, 앞에서 만든 EBS 스냅숏이나 덤프로 복원합니다. 덤프 복원은 `mysql.db`의 권한을 되돌리지 않으므로 복원 뒤 `SHOW GRANTS`로 모듈 스키마(`paldogotgan_*`) 권한이 남았는지 확인하고, 남았으면 거두거나 모듈 스키마를 지웁니다.

### 운영 적용 전에 확인할 것

이 절차는 로컬 임시 MySQL에서만 리허설했습니다. 운영에 적용하기 전에 다음을 확인합니다.

- **운영 데이터 규모**: `SELECT table_name, table_rows, ROUND((data_length + index_length) / 1024 / 1024) AS mb FROM information_schema.tables WHERE table_schema = '<MYSQL_DATABASE 값>' ORDER BY mb DESC;`로 큰 테이블을 봅니다. `table_rows`는 추정치입니다.
- **점검 시간 예상**: 앱 중지 시간 = 백업 시간 + 이전(순간) + 이전 전후 비교. 이 중 비교(`CHECKSUM TABLE`)가 데이터 크기에 비례합니다. 운영 스냅숏을 복원한 DB에서 `migrate.sh all`을 실제로 실행해 걸리는 시간을 재고, 너무 길면 `SNAPSHOT_MODE=rows`(행 수만 비교)로 줄일지 정합니다. `rows`는 체크섬 비교를 하지 않으므로 그 만큼 검증이 약해집니다.
- **MySQL 권한 설정**: `SELECT @@partial_revokes;`. `ON`이면 와일드카드 권한이 문자 그대로 해석되는데, 이 절차는 스키마 8개를 하나씩 명시해 권한을 주므로 영향이 없어야 합니다. `20_verify.sql`의 권한 점검이 PASS인지 확인합니다.
- **알려진 마이그레이션 목록**: `00_preflight.sql`의 목록은 이 PR 시점의 55개입니다. 이후 병합된 마이그레이션이 운영 DB에 이미 적용돼 있으면 사전 점검이 멈추고, `migrate.sh`가 저장소의 마이그레이션 파일과 목록이 다르면 경고를 출력합니다. 그 경우 목록(`00_preflight.sql`)과 `20_verify.sql`의 행 수, `migrate.sh`의 이력 합계(54)를 새 마이그레이션까지 포함하도록 고친 뒤 실행합니다.
- **앱 계정 권한**: 앱 계정이 모든 모듈 스키마에 `ALL` 권한을 가집니다. 모듈 단독 실행(개발)에는 모듈별 계정이 있지만([`DEVELOPMENT.md`의 모듈 DB 계정](DEVELOPMENT.md#스키마계정-생성)), 운영 `monolith`는 이 공용 계정을 씁니다.

## 9. JWT 서명 키 (#313)

Access Token은 RS256(비대칭키)으로 서명합니다. 서버의 `/opt/paldo-gotgan/keys`에 키 PEM 파일과 키 설정 파일(`jwt.yaml`)을 두고, `compose.prod.yml`이 이 폴더를 앱 컨테이너의 `/run/secrets/jwt`에 읽기 전용으로 마운트합니다.

```
/opt/paldo-gotgan/keys/
├── jwt.yaml               어떤 키로 검증·서명하는지 (예시: deploy/jwt.example.yaml)
├── rs256-v1-private.pem   서명용 개인키
└── rs256-v1-public.pem    검증용 공개키
```

- 앱은 `application-prod.yml`에서 `jwt.yaml`을 읽어 이미지 안의 키 설정(`jwt.verification-keys`, `jwt.signing`)을 덮어씁니다. 그래서 키를 교체·폐기할 때 새 이미지 없이 이 폴더만 바꾸고 앱을 재시작합니다.
- `jwt.yaml`이 없거나, 키 파일이 없거나, 형식이 맞지 않거나, 개인키가 서명 `kid`의 공개키와 짝이 아니면 앱이 기동하지 않습니다.
- 파일 이름은 키 식별자(`kid`)를 따릅니다(`<kid>-private.pem`, `<kid>-public.pem`). 교체 중에는 옛 키와 새 키가 한 폴더에 함께 있기 때문입니다.
- 키는 백업하지 않습니다. 서버를 잃으면 새 키를 만들면 되고, 사용자는 Refresh Token으로 새 Access Token을 받습니다(Refresh Token은 JWT가 아니라 DB에 있음).

### 키 만들기 (최초 설정, 또는 HS256에서 처음 전환할 때)

로컬·개발 환경과 다른 키를 서버에서 직접 만듭니다. 개인키를 다른 곳으로 복사하거나 저장소에 올리지 않습니다.

```bash
# 로컬: 키 설정 예시를 서버로 올린다 (키 내용은 없고 kid와 컨테이너 경로만 있다)
scp -i <키 파일> deploy/jwt.example.yaml ubuntu@<EIP>:/tmp/jwt.yaml

# 서버
cd /opt/paldo-gotgan
mkdir -p keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/rs256-v1-private.pem
openssl pkey -in keys/rs256-v1-private.pem -pubout -out keys/rs256-v1-public.pem
mv /tmp/jwt.yaml keys/jwt.yaml
# 컨테이너 사용자(uid 1001, Dockerfile의 appuser)만 개인키를 읽게 한다
sudo chown -R 1001 keys && sudo chmod 500 keys && sudo chmod 400 keys/*
```

- 이후 키 폴더의 파일을 읽거나 바꾸려면 `sudo`가 필요합니다. `ubuntu` 사용자는 개인키를 읽을 수 없습니다.

### HS256에서 처음 전환하는 배포

0. 프론트엔드가 401을 받으면 Refresh Token으로 재발급받고 요청을 다시 보내는지 확인합니다. 배포 직후 기존 토큰이 모두 401을 받기 때문입니다.
1. 위 절차로 키와 `jwt.yaml`을 만듭니다.
2. [6. 배포 파일 갱신](#6-배포-파일-갱신)으로 새 `compose.prod.yml`을 서버에 올리고 `./deploy.sh`로 배포합니다.
3. 새 버전이 안정되면 `.env`의 `JWT_SECRET` 줄과 `compose.prod.yml`의 `JWT_SECRET` 줄을 함께 지웁니다. 새 버전은 이 값을 읽지 않습니다.

- 배포 전에 발급된 HS256 Access Token은 배포 직후부터 401을 받습니다. 클라이언트는 Refresh Token으로 재발급받으면 되고, Refresh Token은 JWT가 아니라서 영향이 없습니다. HS256 토큰을 함께 받아들이는 기간은 두지 않습니다(비밀키가 계속 남기 때문).
- 이전 버전 이미지는 `JWT_SECRET`으로 기동합니다. 그래서 `compose.prod.yml`은 3번 전까지 이 값을 앱에 계속 넘기고, 그동안은 [4. 롤백](#4-롤백)처럼 `APP_IMAGE_TAG`만 바꿔 되돌릴 수 있습니다. 3번 뒤에는 이전 이미지로 롤백할 수 없으므로, 3번은 새 버전이 안정된 것을 확인한 뒤에 합니다.
- 롤백하면 이전 이미지가 다시 HS256 토큰을 발급합니다. 그래서 고친 RS256 버전을 다시 배포할 때도 그 사이에 발급된 Access Token이 401을 받습니다(최대 30분). 처음 전환할 때와 같은 영향이므로 따로 할 일은 없습니다.

### 키 교체

정기 교체는 하지 않습니다. 개인키 유출이 의심되거나 서버에 접근할 수 있는 사람이 바뀌었을 때 교체합니다. 이미지 배포 없이 서버에서 끝납니다. 예: `rs256-v1` → `rs256-v2`

```bash
# 서버
cd /opt/paldo-gotgan
sudo openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/rs256-v2-private.pem
sudo openssl pkey -in keys/rs256-v2-private.pem -pubout -out keys/rs256-v2-public.pem
sudo chown 1001 keys/rs256-v2-* && sudo chmod 400 keys/rs256-v2-*
sudo vi keys/jwt.yaml          # 아래 1번처럼 고친다
docker compose -f compose.prod.yml restart app
```

1. `jwt.yaml`의 `verification-keys`에 `rs256-v2`를 **추가**하고, `signing`을 `rs256-v2`와 새 개인키로 바꾼 뒤 재시작합니다. 새 토큰은 `rs256-v2`로 서명되고, 이전 토큰은 `rs256-v1` 공개키로 만료될 때까지 검증됩니다.
2. Access Token 유효 기간(30분)이 지나면 `verification-keys`에서 `rs256-v1`을 빼고 재시작한 뒤 `rs256-v1-*.pem`을 지웁니다.

- 앱이 monolith 하나라 검증 목록 추가와 서명 키 변경을 한 번에 합니다. 검증하는 앱이 여럿이면 순서가 필요합니다([`DEVELOPMENT.md`의 키 교체](DEVELOPMENT.md#키-관리와-교체)).
- 개인키가 유출되었으면 2를 기다리지 않고 1에서 `rs256-v1`을 바로 뺍니다. 그 키로 서명된 토큰은 모두 거부되고, 사용자는 Refresh Token으로 재발급받습니다.
- 재시작 중 수십 초간 요청이 실패합니다([1. 구성](#1-구성)).
