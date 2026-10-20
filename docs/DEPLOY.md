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
scp -i <키 파일> -r deploy/compose.prod.yml deploy/deploy.sh deploy/nginx deploy/.env.prod.example ubuntu@<EIP>:/tmp/

# 서버
ssh -i <키 파일> ubuntu@<EIP>
docker login ghcr.io -u <GitHub 사용자>   # Password: 에 pull 전용 토큰 (화면에 표시되지 않음)
chmod 600 ~/.docker/config.json             # 토큰이 base64로만 저장되므로 다른 사용자가 읽지 못하게 한다
sudo mkdir -p /opt/paldo-gotgan && sudo chown ubuntu:ubuntu /opt/paldo-gotgan
mv /tmp/compose.prod.yml /tmp/deploy.sh /tmp/nginx /tmp/.env.prod.example /opt/paldo-gotgan/
cd /opt/paldo-gotgan
cp .env.prod.example .env && chmod 600 .env
vi .env                      # 비밀번호·키 채우기
mountpoint /data             # "/data is a mountpoint"가 나와야 한다
./deploy.sh
```

## 3. 배포

이미지는 dev에 머지되면 GitHub Actions(`.github/workflows/docker-image.yml`)가 빌드해 GHCR에 `latest`와 커밋 SHA 7자리 태그로 올립니다. 서버에는 dev에 머지된 코드만 배포합니다. 머지되지 않은 브랜치로 배포하면, 나중에 dev에 들어오는 더 이른 번호의 마이그레이션 때문에 Flyway 검증이 실패해 앱이 뜨지 않습니다.

1. dev 머지 후 Actions 탭에서 `Docker Image` 실행이 성공했는지 확인합니다.
2. 서버에서 배포합니다.

```bash
cd /opt/paldo-gotgan && ./deploy.sh
```

- 패키지는 비공개로 둡니다. 서버는 [2. 최초 설정](#2-최초-설정-서버당-1회)에서 로그인한 pull 전용 토큰으로 받습니다.
- 서버는 `latest`를 받고, SHA 태그는 롤백에 씁니다. 빌드는 한 번에 하나씩 실행되고 대기 중인 실행은 새 실행이 오면 취소되므로, 연속으로 머지하면 중간 커밋의 SHA 태그는 없을 수 있습니다. 롤백할 SHA는 GHCR 패키지 화면에서 실제 있는 태그를 확인합니다.
- 빌드만 다시 하려면 Actions 탭에서 `Docker Image` → Run workflow로 실행합니다. dev가 아닌 브랜치를 고르면 빌드는 건너뜁니다.
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
