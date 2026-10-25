# 배포 가이드

EC2 한 대에 nginx·앱·MySQL을 Docker 컨테이너로 띄워 수동으로 배포하는 방법입니다.
서버(EC2) 자체를 만드는 방법은 [`INFRA.md`](INFRA.md)를 보세요.

## 1. 구성

```
인터넷 ──80──▶ nginx ──/api/v1/**──▶ app ──▶ mysql
                 └─ 그 외 경로(/internal, /actuator 등) → 404
```

- 외부에는 `/api/v1/**`만 공개합니다. 인증·인가는 앱(JWT)이 처리합니다.
- `/internal/**`은 모듈 간 내부 호출용이라 인증이 없습니다. nginx가 404로 막고, 앱도 같은 컨테이너(loopback)에서 온 요청만 받습니다.
  - 그래서 내부 API 클라이언트의 `app.internal-api.base-url`은 기본값(`http://localhost:8080`)을 유지해야 합니다. nginx 주소로 바꾸면 내부 호출이 막힙니다.
- app·mysql은 호스트 포트를 열지 않습니다.
- 파일: 레포의 `deploy/` → 서버의 `/opt/paldo-gotgan`
- 주소: `http://2000k.duckdns.org` (DuckDNS 무료 도메인, EIP를 가리킨다)
- 이미지: GHCR `ghcr.io/prgrms-be-adv-devcourse/paldo-gotgan` (비공개 패키지). 서버는 pull 전용 토큰으로 로그인해 받습니다.
- 앱은 1개만 띄웁니다. 배포 중 수십 초간 요청이 실패합니다. 여러 개를 띄우려면 정산 스케줄러 중복 실행 문제를 먼저 해결해야 합니다.

## 2. 최초 설정 (서버당 1회)

> **참고:** 공개 API(로그인·회원가입, 상품 조회 등)를 뺀 `/api/v1` 요청은 로그인이 필요합니다(#242).

먼저 [3. 배포](#3-배포)처럼 GHCR에 이미지가 올라가 있는지 확인합니다. 이미지가 없으면 아래 마지막 `./deploy.sh`가 pull에 실패합니다.

서버가 비공개 패키지를 받으려면 pull 전용 토큰이 필요합니다. GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)에서 만듭니다.

- 권한은 `read:packages`만 줍니다. push용 토큰을 재사용하지 않습니다. classic 토큰은 계정이 접근할 수 있는 모든 패키지를 읽을 수 있어서 권한을 최소로 둡니다.
- 만료일을 지정하고 따로 적어 둡니다. 만료되면 `./deploy.sh`가 pull 단계에서 실패합니다.
- 조직이 SSO를 쓰면 발급 후 Configure SSO에서 조직을 승인합니다.

```bash
# 로컬: 배포 파일 복사
scp -i <키 파일> -r deploy/compose.prod.yml deploy/deploy.sh deploy/nginx deploy/initdb deploy/.env.prod.example ubuntu@<EIP>:/tmp/

# 서버
ssh -i <키 파일> ubuntu@<EIP>
docker login ghcr.io -u <GitHub 사용자>   # Password: 에 pull 전용 토큰 (화면에 표시되지 않음)
chmod 600 ~/.docker/config.json             # 토큰이 base64로만 저장되므로 다른 사용자가 읽지 못하게 한다
sudo mkdir -p /opt/paldo-gotgan && sudo chown ubuntu:ubuntu /opt/paldo-gotgan
mv /tmp/compose.prod.yml /tmp/deploy.sh /tmp/nginx /tmp/initdb /tmp/.env.prod.example /opt/paldo-gotgan/
cd /opt/paldo-gotgan
cp .env.prod.example .env && chmod 600 .env
vi .env                      # 비밀번호·키 채우기
mountpoint /data             # "/data is a mountpoint"가 나와야 한다
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
curl -s -o /dev/null -w '%{http_code}\n' localhost/api/v1/products/categories   # 200
curl -s -o /dev/null -w '%{http_code}\n' localhost/internal/v1/products/options # 404
```

## 6. 배포 파일 갱신

`deploy.sh`는 앱 이미지만 새로 받습니다. `compose.prod.yml`이나 `nginx/default.conf`가 바뀌면 아래 순서로 반영합니다.

```bash
# 로컬: 기존 파일 위에 덮어쓴다 (디렉터리를 mv로 옮기면 기존 디렉터리 안에 중첩되므로 목적지 경로를 직접 지정한다)
scp -i <키 파일> deploy/compose.prod.yml ubuntu@<EIP>:/opt/paldo-gotgan/compose.prod.yml
scp -i <키 파일> deploy/nginx/default.conf ubuntu@<EIP>:/opt/paldo-gotgan/nginx/default.conf

# 서버
cd /opt/paldo-gotgan
./deploy.sh
docker compose -f compose.prod.yml restart nginx
```

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
- 반대로, 이전한 DB에 **이전 버전 이미지를 띄우면** 기본 스키마에 빈 테이블이 새로 생기고 데이터가 갈라질 수 있어서, 이전 스크립트가 기본 스키마에 자리표시 테이블 `media`를 둡니다. 이전 버전 앱은 첫 마이그레이션(`media` 테이블 생성)에서 `already exists`로 멈춥니다. 되돌릴 때는 스크립트가 이 테이블을 지웁니다.
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

### 이전이 도중에 실패했을 때

`migrate.sh all`은 이전(`10_forward.sql`) 중에 실패하면 현재 상태와 다음 할 일을 출력합니다. 앱은 올리지 않습니다.

| 실패한 곳 | 상태 | 할 일 |
|---|---|---|
| 사전 점검 | 아무것도 바뀌지 않음 | 실패한 점검(`preflight.log`)을 고치고 다시 실행 |
| 스키마 생성·이력 복사·`RENAME TABLE` (예: 열린 트랜잭션 때문에 락 대기 시간 초과) | 테이블은 그대로, 빈 모듈 스키마와 이력 복사본만 있음 | `./migrate.sh cleanup ...`으로 정리하고 원인을 고친 뒤 처음부터 다시 실행 |
| `RENAME TABLE` 뒤(기본 이력 정리, 자리표시 테이블, 사후 점검, 전후 비교) | 테이블은 옮겨졌고 일부 정리가 덜 됨 | `./migrate.sh rollback ...`로 되돌린 뒤 원인을 고쳐 다시 실행(되돌리기는 도중에 실패한 상태에서도 다시 실행할 수 있다) |

### 이전 되돌리기

새 앱을 멈춘 뒤, 이전 후 새 마이그레이션이 적용되기 전일 때만 스크립트로 되돌립니다. 새 앱이 쓴 데이터도 함께 원래 스키마로 돌아옵니다.

```bash
docker compose -f compose.prod.yml stop app nginx
./migrate.sh rollback <MYSQL_DATABASE 값> <MYSQL_USER 값> /data/migrate-288-out   # 같은 결과 폴더를 넘긴다
APP_IMAGE_TAG=<이전 SHA 태그> ./deploy.sh                                          # 이전 이미지로 배포
```

- 이전 버전 이미지는 **되돌리기를 먼저 한 뒤에** 배포합니다. 되돌리기 전에 이전 이미지를 띄우면 앞에서 설명한 대로 멈추는데, 이때 기본 스키마 이력에 실패한 마이그레이션 행이 남습니다. 그러면 `rollback`이 기본 이력이 1행이 아니라며 거부하므로, 안내대로 실패 행을 지운 뒤(`DELETE FROM <기본 스키마>.flyway_schema_history WHERE success = 0`) 다시 실행합니다.
- 되돌린 뒤 행 수·체크섬이 되돌리기 직전과 같은지 스크립트가 비교합니다. 이전 후 새 마이그레이션이 적용됐거나 스크립트가 실패하면, 앞에서 만든 EBS 스냅숏이나 덤프로 복원합니다. 덤프 복원은 `mysql.db`의 권한을 되돌리지 않으므로 복원 뒤 `SHOW GRANTS`로 모듈 스키마(`paldogotgan_*`) 권한이 남았는지 확인하고, 남았으면 거두거나 모듈 스키마를 지웁니다.

### 운영 적용 전에 확인할 것

이 절차는 로컬 임시 MySQL에서만 리허설했습니다. 운영에 적용하기 전에 다음을 확인합니다.

- **운영 데이터 규모**: `SELECT table_name, table_rows, ROUND((data_length + index_length) / 1024 / 1024) AS mb FROM information_schema.tables WHERE table_schema = '<MYSQL_DATABASE 값>' ORDER BY mb DESC;`로 큰 테이블을 봅니다. `table_rows`는 추정치입니다.
- **점검 시간 예상**: 앱 중지 시간 = 백업 시간 + 이전(순간) + 이전 전후 비교. 이 중 비교(`CHECKSUM TABLE`)가 데이터 크기에 비례합니다. 운영 스냅숏을 복원한 DB에서 `migrate.sh all`을 실제로 실행해 걸리는 시간을 재고, 너무 길면 `SNAPSHOT_MODE=rows`(행 수만 비교)로 줄일지 정합니다. `rows`는 체크섬 비교를 하지 않으므로 그 만큼 검증이 약해집니다.
- **MySQL 권한 설정**: `SELECT @@partial_revokes;`. `ON`이면 와일드카드 권한이 문자 그대로 해석되는데, 이 절차는 스키마 8개를 하나씩 명시해 권한을 주므로 영향이 없어야 합니다. `20_verify.sql`의 권한 점검이 PASS인지 확인합니다.
- **알려진 마이그레이션 목록**: `00_preflight.sql`의 목록은 이 PR 시점의 55개입니다. 이후 병합된 마이그레이션이 운영 DB에 이미 적용돼 있으면 사전 점검이 멈추고, `migrate.sh`가 저장소의 마이그레이션 파일과 목록이 다르면 경고를 출력합니다. 그 경우 목록(`00_preflight.sql`)과 `20_verify.sql`의 행 수, `migrate.sh`의 이력 합계(54)를 새 마이그레이션까지 포함하도록 고친 뒤 실행합니다.
- **앱 계정 권한**: 앱 계정이 모든 모듈 스키마에 `ALL` 권한을 가집니다(모듈별 계정 분리는 후속 과제).
