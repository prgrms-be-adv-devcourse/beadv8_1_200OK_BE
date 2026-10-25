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
- 스크립트: `infra/mysql/migration-288/` (`migrate.sh`가 점검 → 이전 → 사후 점검을 이어서 실행하고, 실패하면 바로 멈춥니다). 서버에서 실행하려면 이 폴더를 `scp`로 서버에 복사합니다.
- 이전 중에는 앱이 꺼져 있어야 합니다(쓰기 중단). 이전은 한 번에 끝나고 데이터 크기와 무관하게 짧습니다.

```bash
# 서버 (/opt/paldo-gotgan)
docker compose -f compose.prod.yml stop app nginx          # 쓰기 중단
# 백업: 덤프와 EBS 스냅숏
docker compose -f compose.prod.yml exec -T mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --databases "$MYSQL_DATABASE"' > /data/backup-before-288.sql
# 이전 (root 계정으로 mysql을 실행하는 명령을 MYSQL_CMD로 넘긴다. 명령 기록에 비밀번호가 남지 않게 실행 뒤 history에서 지운다)
export MYSQL_CMD='docker compose -f compose.prod.yml exec -T mysql mysql -uroot -p<MYSQL_ROOT_PASSWORD 값>'
./migrate.sh all <MYSQL_DATABASE 값> <MYSQL_USER 값> /data/migrate-288-out
```

- `migrate.sh`는 먼저 사전 점검을 하고(테이블 목록이 예상과 같은지, 알려진 55개 외의 마이그레이션 이력이 없는지, 트리거·뷰·테이블 권한이 없는지, 앱 연결·열린 트랜잭션이 없는지, 대상 스키마가 비어 있는지), 하나라도 실패하면 아무것도 바꾸지 않고 멈춥니다. 병합되지 않은 브랜치의 마이그레이션이 이력에 있으면 중단되니 그 브랜치를 정리한 뒤 다시 실행합니다.
- 이전 전후로 모든 테이블의 행 수와 `CHECKSUM TABLE` 값을 결과 폴더(`before.txt`, `after.txt`)에 남기고 같은지 비교합니다. 다르면 실패로 끝납니다.
- 이전 뒤 새 버전을 배포하고(`./deploy.sh`) 확인합니다: health UP, 로그인, 상품 조회, 지갑 잔액, 정산 조회, 가입 후 지갑이 생기는지. 앱 로그에서 Flyway가 스키마 9곳을 검증하고 새로 적용한 마이그레이션이 0건인지 봅니다.

### 이전 되돌리기

새 앱을 멈춘 뒤, 이전 후 새 마이그레이션이 적용되기 전일 때만 스크립트로 되돌립니다. 새 앱이 쓴 데이터도 함께 원래 스키마로 돌아옵니다.

```bash
docker compose -f compose.prod.yml stop app nginx
./migrate.sh rollback <MYSQL_DATABASE 값> <MYSQL_USER 값> /data/migrate-288-out   # 같은 결과 폴더를 넘긴다
APP_IMAGE_TAG=<이전 SHA 태그> ./deploy.sh                                          # 이전 이미지로 배포
```

- 되돌린 뒤 행 수·체크섬이 되돌리기 직전과 같은지 스크립트가 비교합니다. 이전 후 새 마이그레이션이 적용됐거나 스크립트가 실패하면, 앞에서 만든 덤프(`/data/backup-before-288.sql`)나 EBS 스냅숏으로 복원합니다.
- 이전 이미지로 되돌릴 때는 되돌리기를 먼저 해야 합니다. 이 버전 이후 코드는 이전된 DB에서만, 이전 코드는 이전되지 않은 DB에서만 기동합니다.
