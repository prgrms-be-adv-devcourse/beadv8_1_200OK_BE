#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

exec 9>/tmp/paldo-infra-up.lock
flock -n 9 || { echo "이미 실행 중이다" >&2; exit 1; }

[ -f .env ] || { echo ".env가 없다. .env.example을 복사해 값을 채운다" >&2; exit 1; }
[ -f initdb/10-create-module-schemas.sh ] || { echo "initdb/가 없다. 레포의 deploy/initdb를 함께 복사한다" >&2; exit 1; }

INFRA_PRIVATE_IP=$(grep -E '^INFRA_PRIVATE_IP=' .env | tail -1 | cut -d= -f2- | tr -d '\r"'"'" || true)
[ -n "$INFRA_PRIVATE_IP" ] || { echo ".env에 INFRA_PRIVATE_IP가 필요하다" >&2; exit 1; }
if ! ip -4 -o addr show | grep -qw "$INFRA_PRIVATE_IP"; then
  echo "INFRA_PRIVATE_IP($INFRA_PRIVATE_IP)가 이 서버의 주소가 아니다" >&2
  exit 1
fi

if ! docker compose up -d --wait --wait-timeout 180; then
  echo "실패: healthy가 되지 않은 서비스가 있다" >&2
  docker compose ps >&2
  docker compose logs --tail 100 >&2
  exit 1
fi

docker compose ps
echo "완료: mysql ${INFRA_PRIVATE_IP}:3306, redis ${INFRA_PRIVATE_IP}:6379, kafka ${INFRA_PRIVATE_IP}:9092"
