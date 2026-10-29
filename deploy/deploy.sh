#!/usr/bin/env bash
# 앱 이미지를 받아 재시작하고 healthy가 될 때까지 기다린다. 절차는 docs/DEPLOY.md
# 이후 자동 배포(webhook)도 이 스크립트를 그대로 실행한다
set -euo pipefail
cd "$(dirname "$0")"

# 로컬 검증에서는 COMPOSE_FILE로 override 파일을 덧붙인다
export COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yml}"

HEALTH_RETRIES=24   # 5초 × 24 = 최대 2분
HEALTH_INTERVAL=5

exec 9>/tmp/paldo-deploy.lock
flock -n 9 || { echo "이미 배포 중이다" >&2; exit 1; }

docker compose pull app

# nginx가 app healthy를 기다리므로 전체를 한 번에 up -d 하면 app이 비정상일 때 로그를 찍기 전에 실패한다.
# 그래서 app(과 mysql)만 먼저 올리고 healthy를 확인한 뒤 나머지를 올린다
if ! docker compose up -d app; then
  echo "배포 실패: app 기동 중 오류가 발생했다" >&2
  docker compose logs --tail 100 app mysql >&2
  exit 1
fi

CID=$(docker compose ps -q app)
[ -n "$CID" ] || { echo "app 컨테이너를 찾지 못했다" >&2; exit 1; }

for _ in $(seq 1 "$HEALTH_RETRIES"); do
  STATUS=$(docker inspect -f '{{.State.Health.Status}}' "$CID" 2>/dev/null || echo unknown)
  if [ "$STATUS" = healthy ]; then
    if ! docker compose up -d; then
      echo "배포 실패: app은 healthy지만 나머지 서비스 기동에 실패했다" >&2
      exit 1
    fi
    echo "배포 성공: $(docker inspect -f '{{.Config.Image}}' "$CID")"
    exit 0
  fi
  sleep "$HEALTH_INTERVAL"
done

echo "배포 실패: 2분 안에 healthy가 되지 않았다 (마지막 상태: $STATUS)" >&2
docker compose logs --tail 100 app >&2
exit 1
