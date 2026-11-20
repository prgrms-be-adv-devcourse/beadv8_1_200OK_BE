#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

WAIT_TIMEOUT="${WAIT_TIMEOUT:-600}"
MODULES=(media member wallet order product delivery payout refund)

exec 9>/tmp/paldo-app-deploy.lock
flock -n 9 || { echo "이미 배포 중이다" >&2; exit 1; }

env_value() {
  grep -E "^$1=" .env | tail -1 | cut -d= -f2- | tr -d '\r' | sed -E "s/^[\"']//; s/[\"']$//" || true
}

[ -f .env ] || { echo ".env가 없다. .env.example을 복사해 값을 채운다" >&2; exit 1; }
[ "$(stat -c %a .env)" = 600 ] || { echo ".env 권한을 600으로 둔다: chmod 600 .env" >&2; exit 1; }
for f in compose.yml router.conf nginx.conf; do
  [ -f "$f" ] || { echo "$f가 없다. 레포의 deploy/app을 함께 복사한다" >&2; exit 1; }
done
for f in keys/rs256-v1-private.pem keys/rs256-v1-public.pem; do
  [ -r "$f" ] || { echo "$f가 없다 (member가 JWT 서명·JWKS에 쓴다)" >&2; exit 1; }
done

INFRA_PRIVATE_IP="$(env_value INFRA_PRIVATE_IP)"
[ -n "$INFRA_PRIVATE_IP" ] || { echo ".env에 INFRA_PRIVATE_IP가 필요하다" >&2; exit 1; }
for port in 3306 6379 9092; do
  if ! timeout 3 bash -c "exec 3<>/dev/tcp/${INFRA_PRIVATE_IP}/${port}" 2>/dev/null; then
    echo "infra ${INFRA_PRIVATE_IP}:${port}에 닿지 않는다. infra 서버의 infra-up.sh와 보안 그룹을 확인한다" >&2
    exit 1
  fi
done

OLD=$(docker ps -q --filter label=com.docker.compose.project=paldo-gotgan)
if [ -n "$OLD" ]; then
  echo "이전 monolith 스택(paldo-gotgan)이 떠 있다. 80 포트가 겹친다." >&2
  echo "내리려면: cd /opt/paldo-gotgan && docker compose -f compose.prod.yml stop" >&2
  exit 1
fi

docker compose config --quiet

if ! docker compose pull "${MODULES[@]}" router nginx; then
  echo "배포 실패: 이미지를 받지 못했다. GHCR 로그인(docker login ghcr.io)과 APP_IMAGE_TAG를 확인한다" >&2
  exit 1
fi

if ! docker compose up -d --remove-orphans --wait --wait-timeout "$WAIT_TIMEOUT"; then
  echo "배포 실패: ${WAIT_TIMEOUT}초 안에 healthy가 되지 않은 서비스가 있다" >&2
  docker compose ps -a >&2
  for s in "${MODULES[@]}"; do
    STATUS=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$(docker compose ps -aq "$s")" 2>/dev/null || echo missing)
    if [ "$STATUS" != healthy ]; then
      echo "----- $s ($STATUS)" >&2
      docker compose logs --tail 80 "$s" >&2
    fi
  done
  exit 1
fi

docker compose ps
for s in "${MODULES[@]}"; do
  printf '%s %s\n' "$s" "$(docker inspect -f '{{.Config.Image}}' "$(docker compose ps -q "$s")")"
done
echo "배포 성공"
