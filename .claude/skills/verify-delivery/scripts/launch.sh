#!/usr/bin/env bash
# delivery 모듈 단독 검증 인스턴스를 띄운다. 사용: scripts/launch.sh
# 마지막 줄에 상태 파일 경로를 출력한다. 이후 스크립트는 VD_STATE=<그 경로> 로 같은 인스턴스를 가리킨다.
set -euo pipefail

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$SKILL_DIR/../../.." && pwd)"
MYSQL_IMAGE="${VD_MYSQL_IMAGE:-mysql:9.4}"
ROOT_PW="vd-root-$RANDOM$RANDOM"   # 검증용 일회용 컨테이너라 root 계정을 쓴다. 팀 규칙의 모듈 계정(delivery/.env)은 쓰지 않는다
READY_TIMEOUT="${VD_READY_TIMEOUT:-600}"

RUN_ID="$(date +%Y%m%d-%H%M%S)-$$"
EVID_ROOT="$(mktemp -d -t verify-delivery-evidence)"   # 정리가 지우지 않는 위치
EVID="$EVID_ROOT/$RUN_ID"
WORK="$(mktemp -d -t verify-delivery-work)"            # 사본 트리. 정리가 지운다
mkdir -p "$EVID"
STATE="$EVID/state.env"
CONTAINER="vd-mysql-$RUN_ID"

# 실패해도 시작한 것은 남기지 않는다. 상태 파일을 먼저 쓰고 cleanup.sh 에 맡긴다
# 경로에 공백이 있을 수 있어 %q 로 따옴표 처리해 source 가능한 형식으로 쓴다
state_set() { printf '%s=%q\n' "$1" "$2" >> "$STATE"; }
: > "$STATE"
state_set RUN_ID "$RUN_ID"; state_set REPO "$REPO"; state_set EVID "$EVID"; state_set WORK "$WORK"
state_set CONTAINER_NAME "$CONTAINER"; state_set ROOT_PW "$ROOT_PW"
fail() { echo "launch 실패: $1 (로그: $EVID)" >&2; VD_STATE="$STATE" "$SKILL_DIR/scripts/cleanup.sh" >&2 || true; exit 1; }

# 1) 사본 트리. iCloud 가 만든 이름 끝 " 2" 사본 파일이 있으면 common 컴파일이 깨지므로 제외한다.
#    실제 저장소의 .env, keys, delivery/.env 는 읽지 않도록 제외하고, 거대한 data/ 와 terraform 도 뺀다.
rsync -a \
  --exclude='build/' --exclude='.gradle/' --exclude='.git/' --exclude='.idea/' \
  --exclude='* 2*' --exclude='.env' --exclude='keys/' \
  --exclude='/data/' --exclude='/infra/terraform/' \
  "$REPO/" "$WORK/" || fail "rsync"

# 2) JWT 키쌍 (사본 안에서 생성)
mkdir -p "$WORK/keys"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/keys/rs256-v1-private.pem" 2>/dev/null || fail "openssl 개인키"
openssl pkey -in "$WORK/keys/rs256-v1-private.pem" -pubout -out "$WORK/keys/rs256-v1-public.pem" || fail "openssl 공개키"

# 3) 격리된 MySQL. 호스트 포트는 127.0.0.1 의 임의 포트라서 사용자의 3306 과 겹치지 않는다
CID="$(docker run -d --name "$CONTAINER" -e MYSQL_ROOT_PASSWORD="$ROOT_PW" -p 127.0.0.1::3306 \
  "$MYSQL_IMAGE" --default-time-zone=+09:00)" || fail "docker run"
state_set CONTAINER_ID "$CID"
DB_PORT="$(docker port "$CONTAINER" 3306/tcp | head -1 | sed 's/.*://')"
state_set DB_PORT "$DB_PORT"

# --default-time-zone=+09:00: 앱이 Asia/Seoul 로 읽고 쓰므로, 시드 SQL 의 NOW(), CURDATE() 도 같은 시각이 되게 한다
# 헬스체크가 통과해도 init 스크립트가 도는 중일 수 있다. init 완료 로그와 실제 접속 성공을 모두 본다
for i in $(seq 1 90); do
  if docker logs "$CONTAINER" 2>&1 | grep -q "MySQL init process done. Ready for start up." \
     && docker exec "$CONTAINER" mysql -uroot -p"$ROOT_PW" -e 'SELECT 1' >/dev/null 2>&1; then
    DB_READY=1; break
  fi
  sleep 2
done
[ "${DB_READY:-0}" = 1 ] || fail "MySQL 준비 시간 초과"
docker exec "$CONTAINER" mysql -uroot -p"$ROOT_PW" -e 'CREATE DATABASE paldogotgan_delivery' 2>/dev/null || fail "스키마 생성"

# 4) 빈 포트
APP_PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')"
state_set APP_PORT "$APP_PORT"

# 5) 앱. bootRun 이 OK_ROOT_DIR 을 사본 루트로 지정해 사본의 keys 를 읽는다.
#    --no-daemon: 우리가 띄운 Gradle 프로세스 트리만 정리하면 되게 한다
#    env -u: 사용자 셸에 Tracker 시크릿이 export 돼 있어도 앱이 상속하지 못하게 한다.
#            택배사는 mock 이라 쓰이지 않지만, 타입이 바뀌는 실수가 나도 실제 Delivery Tracker 로 호출이 나가지 않게 하는 방어다
(
  cd "$WORK"
  DB_HOST=127.0.0.1 DB_PORT="$DB_PORT" DB_USERNAME=root DB_PASSWORD="$ROOT_PW" \
  SERVER_PORT="$APP_PORT" DELIVERY_CARRIER_TYPE=mock \
  nohup env -u DELIVERY_TRACKER_CLIENT_ID -u DELIVERY_TRACKER_CLIENT_SECRET \
    ./gradlew --no-daemon :delivery:bootRun > "$EVID/app.log" 2>&1 &
  echo $! > "$EVID/app.pid"
)
APP_PID="$(cat "$EVID/app.pid")"
state_set APP_PID "$APP_PID"

# 6) 준비 신호: /actuator/health 가 UP 이고 로그에 Started DeliveryApplication 이 있다
deadline=$((SECONDS + READY_TIMEOUT))
while [ $SECONDS -lt $deadline ]; do
  kill -0 "$APP_PID" 2>/dev/null || { tail -40 "$EVID/app.log" >&2; fail "앱 프로세스가 종료됨(빌드 또는 기동 실패)"; }
  if curl -fsS "http://127.0.0.1:$APP_PORT/actuator/health" 2>/dev/null | grep -q '"status":"UP"' \
     && grep -q "Started DeliveryApplication" "$EVID/app.log"; then
    echo "ready: http://127.0.0.1:$APP_PORT (pid $APP_PID, mysql 127.0.0.1:$DB_PORT)"
    echo "state: $STATE"
    exit 0
  fi
  sleep 3
done
tail -40 "$EVID/app.log" >&2
fail "준비 시간 초과"
