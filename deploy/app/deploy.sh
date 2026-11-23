#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

WAIT_TIMEOUT="${WAIT_TIMEOUT:-600}"
MODULES=(media member wallet order product delivery payout refund)
REQUIRED_MODULE_KEYS=(
  wallet:TOSS_SECRET_KEY
  product:JEV_API_KEY
  product:APP_REVIEWANALYSIS_WORKER_ENABLED
  product:APP_REVIEWANALYSIS_KEYWORDWORKER_ENABLED
  product:APP_PRODUCTTAG_WORKER_ENABLED
  payout:BATCH_JOB_ENABLED
  payout:PAYOUT_SKIP_WAIT_RUN_ENABLED
)

usage() {
  cat >&2 <<'EOF'
사용법:
  ./deploy.sh deploy            .env의 APP_IMAGE_TAG(기본 latest) 이미지로 배포
  ./deploy.sh rollback <태그>   지정한 이미지 태그(커밋 SHA 7자리)로 다시 배포
  ./deploy.sh stop              모든 컨테이너를 내림
  ./deploy.sh status            컨테이너 상태와 이미지
EOF
  exit 1
}

env_value() {
  grep -E "^$1=" "${2:-.env}" | tail -1 | cut -d= -f2- | tr -d '\r' | sed -E "s/^[\"']//; s/[\"']$//" || true
}

require_env_file() {
  [ -f .env ] || { echo ".env가 없다. .env.example을 복사해 값을 채운다" >&2; exit 1; }
  [ "$(stat -c %a .env)" = 600 ] || { echo ".env 권한을 600으로 둔다: chmod 600 .env" >&2; exit 1; }
  for s in "${MODULES[@]}"; do
    local f="env/$s.env"
    [ -f "$f" ] || { echo "$f가 없다. env/$s.env.example을 복사해 값을 채운다" >&2; exit 1; }
    [ "$(stat -c %a "$f")" = 600 ] || { echo "$f 권한을 600으로 둔다: chmod 600 $f" >&2; exit 1; }
  done
}

require_module_keys() {
  local entry s key
  for entry in "${REQUIRED_MODULE_KEYS[@]}"; do
    s="${entry%%:*}"
    key="${entry#*:}"
    [ -n "$(env_value "$key" "env/$s.env")" ] || { echo "env/$s.env에 $key가 필요하다" >&2; exit 1; }
  done
}

preflight() {
  require_env_file
  require_module_keys
  for f in compose.yml nginx.conf; do
    [ -f "$f" ] || { echo "$f가 없다. 레포의 deploy/app을 함께 복사한다" >&2; exit 1; }
  done
  for f in keys/rs256-v1-private.pem keys/rs256-v1-public.pem; do
    [ -r "$f" ] || { echo "$f가 없다 (member가 JWT 서명·JWKS에 쓴다)" >&2; exit 1; }
  done

  local infra_ip
  infra_ip="$(env_value INFRA_PRIVATE_IP)"
  [ -n "$infra_ip" ] || { echo ".env에 INFRA_PRIVATE_IP가 필요하다" >&2; exit 1; }
  for port in 3306 6379 9092; do
    if ! timeout 3 bash -c "exec 3<>/dev/tcp/${infra_ip}/${port}" 2>/dev/null; then
      echo "infra ${infra_ip}:${port}에 닿지 않는다. infra 서버의 infra-up.sh와 보안 그룹을 확인한다" >&2
      exit 1
    fi
  done

  if [ -n "$(docker ps -q --filter label=com.docker.compose.project=paldo-gotgan)" ]; then
    echo "이전 monolith 스택(paldo-gotgan)이 떠 있다. 80 포트가 겹친다." >&2
    echo "내리려면: cd /opt/paldo-gotgan && docker compose -f compose.prod.yml stop" >&2
    exit 1
  fi

  docker compose config --quiet
}

print_failure() {
  docker compose ps -a >&2
  for s in "${MODULES[@]}"; do
    local status
    status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$(docker compose ps -aq "$s")" 2>/dev/null || echo missing)
    if [ "$status" != healthy ]; then
      echo "----- $s ($status)" >&2
      docker compose logs --tail 80 "$s" >&2
    fi
  done
}

print_images() {
  for s in "${MODULES[@]}"; do
    local cid
    cid=$(docker compose ps -q "$s")
    if [ -n "$cid" ]; then
      printf '%-9s %s\n' "$s" "$(docker inspect -f '{{.Config.Image}}' "$cid")"
    else
      printf '%-9s %s\n' "$s" "(없음)"
    fi
  done
}

deploy() {
  preflight
  echo "배포 태그: ${APP_IMAGE_TAG}"

  if ! docker compose pull "${MODULES[@]}" nginx; then
    echo "배포 실패: 이미지를 받지 못했다. GHCR 로그인(docker login ghcr.io)과 태그(${APP_IMAGE_TAG})를 확인한다" >&2
    exit 1
  fi

  if ! docker compose up -d --remove-orphans --wait --wait-timeout "$WAIT_TIMEOUT"; then
    echo "배포 실패: ${WAIT_TIMEOUT}초 안에 healthy가 되지 않은 서비스가 있다 (태그 ${APP_IMAGE_TAG})" >&2
    print_failure
    echo "이전 이미지로 되돌리려면: ./deploy.sh rollback <이전 태그>" >&2
    exit 1
  fi

  docker compose ps
  print_images
  echo "$(date '+%F %T') ${APP_IMAGE_TAG}" >> deploy-history.log
  echo "배포 성공 (태그 ${APP_IMAGE_TAG})"
}

stop() {
  require_env_file
  docker compose down --remove-orphans
  echo "종료 완료. 다시 띄우려면: ./deploy.sh deploy"
}

status() {
  require_env_file
  docker compose ps -a
  print_images
  if [ -f deploy-history.log ]; then
    echo "----- 최근 배포"
    tail -5 deploy-history.log
  fi
}

exec 9>/tmp/paldo-app-deploy.lock
flock -n 9 || { echo "이미 배포 중이다" >&2; exit 1; }

case "${1:-}" in
  deploy)
    [ $# -eq 1 ] || usage
    require_env_file
    APP_IMAGE_TAG="$(env_value APP_IMAGE_TAG)"
    export APP_IMAGE_TAG="${APP_IMAGE_TAG:-latest}"
    deploy
    ;;
  rollback)
    [ $# -eq 2 ] || usage
    [[ "$2" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "태그 형식이 아니다: $2" >&2; exit 1; }
    export APP_IMAGE_TAG="$2"
    deploy
    echo "주의: .env의 APP_IMAGE_TAG는 바꾸지 않았다. 다음 ./deploy.sh deploy는 .env의 태그로 다시 배포한다" >&2
    ;;
  stop)
    [ $# -eq 1 ] || usage
    stop
    ;;
  status)
    [ $# -eq 1 ] || usage
    status
    ;;
  *)
    usage
    ;;
esac
