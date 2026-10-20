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
- 앱은 1개만 띄웁니다. 배포 중 수십 초간 요청이 실패합니다. 여러 개를 띄우려면 정산 스케줄러 중복 실행 문제를 먼저 해결해야 합니다.

## 2. 최초 설정 (서버당 1회)

```bash
# 로컬: 배포 파일 복사
scp -i <키 파일> -r deploy/compose.prod.yml deploy/deploy.sh deploy/nginx deploy/.env.prod.example ubuntu@<EIP>:/tmp/

# 서버
ssh -i <키 파일> ubuntu@<EIP>
sudo mkdir -p /opt/paldo-gotgan && sudo chown ubuntu:ubuntu /opt/paldo-gotgan
mv /tmp/compose.prod.yml /tmp/deploy.sh /tmp/nginx /tmp/.env.prod.example /opt/paldo-gotgan/
cd /opt/paldo-gotgan
cp .env.prod.example .env && chmod 600 .env
vi .env                      # 비밀번호·키 채우기
mountpoint /data             # "/data is a mountpoint"가 나와야 한다
./deploy.sh
```

## 3. 배포

```bash
# 로컬: 이미지 빌드·push (EC2는 amd64라서 플랫폼을 꼭 지정한다)
SHA=$(git rev-parse --short HEAD)
IMAGE=ghcr.io/prgrms-be-adv-devcourse/paldo-gotgan
echo "$GHCR_PAT" | docker login ghcr.io -u <GitHub 사용자> --password-stdin   # write:packages 권한 토큰
docker buildx build --platform linux/amd64 -t "$IMAGE:latest" -t "$IMAGE:$SHA" --push .

# 서버
cd /opt/paldo-gotgan && ./deploy.sh
```

- 처음 push한 뒤 GitHub 패키지 설정에서 `paldo-gotgan` 패키지를 공개로 바꿉니다. 서버는 로그인 없이 pull합니다.
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
