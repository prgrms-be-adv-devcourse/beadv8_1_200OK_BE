#!/usr/bin/env bash
# 앱·BFF 이미지를 받아 재시작하고 healthy가 될 때까지 기다린다. 절차는 docs/DEPLOY.md
#   ./deploy.sh                     전체 배포 (Kafka → app → bff → 나머지)
#   ./deploy.sh app | bff           그 서비스만 다시 띄운다. 다른 컨테이너는 건드리지 않는다
#   ./deploy.sh app | bff --rollback  그 서비스만 직전 정상 이미지로 되돌린다
# 서비스마다 healthy를 확인한 이미지를 digest로 .deploy-state/<서비스>.state에 남긴다 (current, previous)
# 이후 자동 배포(webhook)도 이 스크립트를 그대로 실행한다
set -euo pipefail
cd "$(dirname "$0")"

# 로컬 검증에서는 COMPOSE_FILE로 override 파일을 덧붙인다
export COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yml}"
STATE_DIR="${DEPLOY_STATE_DIR:-.deploy-state}"

HEALTH_RETRIES=24   # 5초 × 24 = 최대 2분
HEALTH_INTERVAL=5

usage() {
  echo "사용법: $0 [app|bff] [--rollback]" >&2
  exit 2
}

TARGET="${1:-all}"
ROLLBACK=false
case "$TARGET" in
  all) [ $# -le 1 ] || usage ;;
  app | bff)
    [ $# -le 2 ] || usage
    case "${2:-}" in
      "") ;;
      --rollback) ROLLBACK=true ;;
      *) usage ;;
    esac
    ;;
  *) usage ;;
esac

# compose.prod.yml은 *_IMAGE_REF가 비어 있으면 .env의 *_IMAGE_TAG를 쓴다.
# .env에 같은 이름이 있어도 무시되도록 비워 두고, 이 스크립트가 받은 이미지의 digest를 넣을 때만 채운다
export APP_IMAGE_REF="" BFF_IMAGE_REF=""

exec 9>/tmp/paldo-deploy.lock
flock -n 9 || { echo "이미 배포 중이다" >&2; exit 1; }

# compose.prod.yml의 image와 같은 레포. 이 레포의 이미지만 digest로 고정하고 이력을 남긴다
repo_for() {
  case $1 in
    app) echo ghcr.io/prgrms-be-adv-devcourse/paldo-gotgan ;;
    bff) echo ghcr.io/prgrms-be-adv-devcourse/paldo-gotgan-bff ;;
  esac
}

# 설정에 적힌 이미지 참조(레포:태그). *_IMAGE_REF가 비어 있으므로 .env의 태그가 들어간다
# config --images는 depends_on으로 이어진 서비스의 이미지도 함께 출력하므로 이 서비스의 레포 줄만 고른다
configured_image() {
  local repo found
  repo=$(repo_for "$1")
  found=$(docker compose config --images "$1" | awk -v r="$repo" 'index($0, r ":") == 1 || index($0, r "@") == 1')
  if [ -z "$found" ] || [ "$(printf '%s\n' "$found" | wc -l)" -ne 1 ]; then
    echo "배포 실패: compose 설정에서 $1 이미지($repo)를 하나로 찾지 못했다" >&2
    return 1
  fi
  echo "$found"
}

ref_var() {
  case $1 in
    app) echo APP_IMAGE_REF ;;
    bff) echo BFF_IMAGE_REF ;;
  esac
}

# compose.prod.yml의 depends_on과 같게 맞춘다. 서비스만 배포할 때는 --no-deps라 compose가 확인하지 않으므로 직접 본다
deps_of() {
  case $1 in
    app) echo mysql kafka-1 kafka-2 kafka-3 ;;
    bff) echo app ;;
  esac
}

# 서비스 상태를 한 단어로 돌려준다: healthy / starting / unhealthy / running(healthcheck 없음) / stopped / none / error
service_status() {
  local cid
  cid=$(docker compose ps -a -q "$1") || { echo error; return; }
  [ -n "$cid" ] || { echo none; return; }
  docker inspect -f '{{if .State.Running}}{{if .State.Health}}{{.State.Health.Status}}{{else}}running{{end}}{{else}}stopped{{end}}' "$cid" 2>/dev/null \
    || echo error
}

# 서비스가 healthy가 될 때까지 기다린다. 2분 안에 안 되면 로그를 찍고 실패를 돌려준다
wait_healthy() {
  local service=$1 status=none
  for _ in $(seq 1 "$HEALTH_RETRIES"); do
    status=$(service_status "$service")
    [ "$status" = healthy ] && return 0
    sleep "$HEALTH_INTERVAL"
  done
  echo "배포 실패: $service가 2분 안에 healthy가 되지 않았다 (마지막 상태: $status)" >&2
  docker compose logs --tail 100 "$service" >&2
  return 1
}

# ---------- 이미지 참조 ----------

valid_digest_ref() {
  [[ $1 =~ ^[^[:space:]@=]+@sha256:[0-9a-f]{64}$ ]]
}

# 로컬 이미지(참조나 ID)의 registry digest 참조(레포@sha256:...)를 찾는다. 없으면 빈 값
digest_of_image() {
  docker image inspect -f '{{range .RepoDigests}}{{println .}}{{end}}' "$1" 2>/dev/null \
    | awk -v prefix="$2@sha256:" 'index($0, prefix) == 1 && length($0) == length(prefix) + 64 { print; exit }' \
    || true
}

# 커밋 SHA 7자리. 태그가 SHA이거나 이미지에 OCI revision 라벨이 있을 때만 알 수 있고, 모르면 빈 값이다 (추측하지 않는다)
sha_of() {
  local image=$1 configured=$2 tag rev
  tag=${configured##*:}
  if [[ $configured != *@* && $tag =~ ^[0-9a-f]{7,40}$ ]]; then
    echo "${tag:0:7}"
    return
  fi
  rev=$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image" 2>/dev/null || true)
  if [[ $rev =~ ^[0-9a-f]{7,40}$ ]]; then
    echo "${rev:0:7}"
  fi
}

# 지금 떠 있는 컨테이너를 본다. RUN_status와, healthy일 때만 RUN_digest·RUN_image·RUN_sha를 채운다
inspect_running() {
  local svc=$1 repo=$2 cid
  RUN_status=$(service_status "$svc")
  RUN_digest="" RUN_image="" RUN_sha=""
  [ "$RUN_status" = healthy ] || return 0
  cid=$(docker compose ps -q "$svc")
  RUN_image=$(docker inspect -f '{{.Config.Image}}' "$cid")
  RUN_digest=$(digest_of_image "$(docker inspect -f '{{.Image}}' "$cid")" "$repo")
  [ -z "$RUN_digest" ] || RUN_sha=$(sha_of "$RUN_digest" "$RUN_image")
}

# ---------- 이력 (.deploy-state/<서비스>.state) ----------
# 키=값 줄만 읽고 아래 키 외에는 무시한다. 셸로 실행(source, eval)하지 않는다

STATE_KEYS="current_image current_digest current_sha current_at previous_image previous_digest previous_sha previous_at"

read_state() {
  local file="$STATE_DIR/$1.state" key value var
  for key in $STATE_KEYS; do
    printf -v "S_$key" '%s' ""
  done
  [ -f "$file" ] || return 0
  while IFS='=' read -r key value; do
    case " $STATE_KEYS " in
      *" $key "*) printf -v "S_$key" '%s' "$value" ;;
    esac
  done < "$file"
  for key in current_digest previous_digest; do
    var=S_$key
    if [ -n "${!var}" ] && ! valid_digest_ref "${!var}"; then
      echo "$file의 $key 값이 digest 형식이 아니다. 파일을 확인한다 (docs/DEPLOY.md)" >&2
      return 1
    fi
  done
}

# 같은 폴더의 임시 파일에 쓴 뒤 mv로 바꿔, 쓰는 도중에 멈춰도 이전 파일이 깨지지 않게 한다
write_state() {
  local svc=$1 file="$STATE_DIR/$1.state" tmp key var
  mkdir -p "$STATE_DIR" || return 1
  tmp=$(mktemp "$STATE_DIR/.$svc.state.XXXXXX") || return 1
  if {
    echo "# deploy.sh가 관리한다. 키=값으로만 읽고 셸로 실행하지 않는다"
    for key in $STATE_KEYS; do
      var=S_$key
      printf '%s=%s\n' "$key" "${!var}"
    done
  } > "$tmp" && mv -f "$tmp" "$file"; then
    return 0
  fi
  rm -f "$tmp"
  return 1
}

now() {
  date '+%Y-%m-%dT%H:%M:%S%z'
}

# 컨테이너를 바꾸기 전에 부른다. 바꾸기 전 정상 이미지를 BEFORE_<서비스>_*에 정한다
# 상태 파일이 없으면 떠 있는 healthy 컨테이너의 실제 digest로 처음 채운다(최초 도입)
prepare_state() {
  local svc=$1 repo=$2 file="$STATE_DIR/$1.state"
  read_state "$svc" || return 1
  inspect_running "$svc" "$repo"

  if [ ! -f "$file" ]; then
    if [ "$RUN_status" = healthy ] && [ -z "$RUN_digest" ]; then
      echo "배포 중단: 떠 있는 $svc 컨테이너는 healthy지만 정확한 이미지(registry digest)를 알 수 없다" >&2
      echo "  원인 예: 로컬에서 빌드한 이미지이거나, 받은 이미지가 지워졌다" >&2
      echo "  이력 없이 시작하려면 빈 상태 파일을 만든 뒤 다시 배포한다. 지금 이미지로는 되돌릴 수 없게 된다" >&2
      echo "    mkdir -p $STATE_DIR && : > $file" >&2
      return 1
    fi
    if [ -n "$RUN_digest" ]; then
      S_current_image=$RUN_image S_current_digest=$RUN_digest S_current_sha=$RUN_sha S_current_at=$(now)
      if ! write_state "$svc"; then
        echo "배포 중단: $file을 만들지 못했다" >&2
        return 1
      fi
      echo "$svc: 이력이 없어 지금 떠 있는 정상 이미지를 기록했다 ($RUN_digest)"
    else
      echo "$svc: 이력도 정상 컨테이너도 없어 이력 없이 시작한다 (상태: $RUN_status)"
    fi
  fi

  # 기준은 실제로 떠 있는 정상 컨테이너다. 없으면(첫 배포, 직전 배포 실패) 마지막으로 기록된 정상 이미지다
  if [ -n "$RUN_digest" ] && [ "$RUN_digest" != "$S_current_digest" ]; then
    printf -v "BEFORE_${svc}_image" '%s' "$RUN_image"
    printf -v "BEFORE_${svc}_digest" '%s' "$RUN_digest"
    printf -v "BEFORE_${svc}_sha" '%s' "$RUN_sha"
    printf -v "BEFORE_${svc}_at" '%s' "unknown"
  else
    printf -v "BEFORE_${svc}_image" '%s' "$S_current_image"
    printf -v "BEFORE_${svc}_digest" '%s' "$S_current_digest"
    printf -v "BEFORE_${svc}_sha" '%s' "$S_current_sha"
    printf -v "BEFORE_${svc}_at" '%s' "$S_current_at"
  fi
}

# 새 이미지가 healthy가 된 뒤에만 부른다. 바꾸기 전과 같은 이미지면 previous를 그대로 둔다
record_success() {
  local svc=$1 image=$2 digest=$3 sha=$4 b
  read_state "$svc" || return 1
  b=BEFORE_${svc}_digest
  if [ -n "${!b}" ] && [ "${!b}" != "$digest" ]; then
    for b in image digest sha at; do
      local var=BEFORE_${svc}_$b
      printf -v "S_previous_$b" '%s' "${!var}"
    done
  fi
  S_current_image=$image S_current_digest=$digest S_current_sha=$sha S_current_at=$(now)
  write_state "$svc"
}

# ---------- 배포 단계 ----------

# 설정의 태그로 받아 둔 이미지의 digest를 PULLED_<서비스>_*에 정한다
resolve_pulled() {
  local svc=$1 image digest
  image=$(configured_image "$svc") || return 1
  digest=$(digest_of_image "$image" "$(repo_for "$svc")")
  if [ -z "$digest" ]; then
    echo "배포 실패: 받은 $svc 이미지($image)의 registry digest를 찾지 못했다" >&2
    return 1
  fi
  printf -v "PULLED_${svc}_image" '%s' "$image"
  printf -v "PULLED_${svc}_digest" '%s' "$digest"
  printf -v "PULLED_${svc}_sha" '%s' "$(sha_of "$image" "$image")"
}

# 정확한 이미지(digest)로 서비스를 다시 만들고 healthy를 확인한 뒤 이력을 남긴다
# 나머지 인자는 docker compose up에 그대로 넘긴다(--no-deps)
start_pinned() {
  local svc=$1 image=$2 digest=$3 sha=$4
  shift 4
  # 이 스크립트 안에서는 계속 이 값을 쓴다. 전체 배포 마지막 up -d가 같은 digest를 보고 다시 만들지 않게 하기 위함이다
  printf -v "$(ref_var "$svc")" '%s' "$digest"
  if ! docker compose up -d "$@" "$svc"; then
    echo "배포 실패: $svc 기동 중 오류가 발생했다" >&2
    docker compose logs --tail 100 "$svc" >&2
    return 1
  fi
  wait_healthy "$svc" || return 1
  if ! record_success "$svc" "$image" "$digest" "$sha"; then
    echo "배포 실패: $svc는 healthy지만 이력($STATE_DIR/$svc.state)을 저장하지 못했다. 컨테이너는 새 이미지로 떠 있다" >&2
    return 1
  fi
  echo "$svc 정상: $digest${sha:+ (SHA $sha)}"
}

# resolve_pulled로 정한 이미지로 start_pinned를 부른다
start_pulled() {
  local svc=$1 image digest sha
  shift
  image=PULLED_${svc}_image digest=PULLED_${svc}_digest sha=PULLED_${svc}_sha
  start_pinned "$svc" "${!image}" "${!digest}" "${!sha}" "$@"
}

# 받기에 실패하면 컨테이너를 하나도 바꾸지 않은 채 멈춘다
pull_or_stop() {
  if ! docker compose pull "$@"; then
    echo "배포 실패: $* 이미지를 받지 못했다. 아무것도 바꾸지 않았다" >&2
    exit 1
  fi
}

fail_hint() {
  echo "  직전 정상 이미지로 되돌리려면: ./deploy.sh $1 --rollback" >&2
  exit 1
}

# ---------- 서비스 하나 (app, bff) ----------

if [ "$TARGET" != all ]; then
  svc=$TARGET
  prepare_state "$svc" "$(repo_for "$svc")" || exit 1

  # --no-deps로 띄우므로 의존 서비스가 정상인지 바꾸기 전에 확인한다
  for dep in $(deps_of "$svc"); do
    status=$(service_status "$dep")
    if [ "$status" != healthy ]; then
      echo "배포 중단: $svc가 기대는 $dep가 healthy가 아니다 (상태: $status). 아무것도 바꾸지 않았다" >&2
      echo "  의존 서비스까지 올리려면 전체 배포(./deploy.sh)를 쓴다" >&2
      exit 1
    fi
  done

  if [ "$ROLLBACK" = true ]; then
    # 지금 컨테이너가 기록된 current와 같은 정상 이미지면 previous로 되돌린다.
    # 정상이 아니거나 이력에 없는 이미지면(직전 배포가 health에서 실패한 경우 등) 마지막 정상 이미지(current)로 되돌린다
    if [ "$RUN_status" = healthy ] && [ -z "$RUN_digest" ]; then
      echo "롤백 중단: 지금 $svc 이미지의 digest를 알 수 없어 되돌릴 대상을 정하지 못했다. 아무것도 바꾸지 않았다" >&2
      exit 1
    fi
    if [ -n "$RUN_digest" ] && [ "$RUN_digest" = "$S_current_digest" ]; then
      to=previous
    else
      to=current
    fi
    var=S_${to}_digest
    if [ -z "${!var}" ]; then
      echo "롤백 중단: $svc의 되돌릴 정상 이미지($to)가 기록돼 있지 않다. 아무것도 바꾸지 않았다" >&2
      exit 1
    fi
    image_var=S_${to}_image sha_var=S_${to}_sha
    echo "$svc 롤백: $to 이미지로 되돌린다 (${!var})"
    # 이 digest로 받는다. 로컬에서 지워졌어도 레지스트리에서 같은 이미지를 받는다
    printf -v "$(ref_var "$svc")" '%s' "${!var}"
    pull_or_stop "$svc"
    # 의존 서비스를 다시 만들지 않도록 이 서비스만 다시 만든다. 전체 up -d도 하지 않는다
    start_pinned "$svc" "${!image_var}" "${!var}" "${!sha_var}" --no-deps || exit 1
  else
    pull_or_stop "$svc"
    resolve_pulled "$svc" || exit 1
    start_pulled "$svc" --no-deps || fail_hint "$svc"
  fi
  echo "배포 성공: $svc"
  exit 0
fi

# ---------- 전체 배포 ----------

prepare_state app "$(repo_for app)" || exit 1
prepare_state bff "$(repo_for bff)" || exit 1

# 둘 중 하나라도 받지 못하면(예: pull 토큰 권한) 아무것도 재시작하기 전에 멈춘다
pull_or_stop app bff
resolve_pulled app || exit 1
resolve_pulled bff || exit 1

# Kafka는 app보다 먼저, 클러스터가 멈추지 않게 1대씩 처리한다 (#318)
# 아래 up -d app이 kafka의 depends_on을 따라 3대를 한꺼번에 다시 만들지 않도록, 여기서 먼저 최신 상태로 맞춰 둔다
if ! ./kafka-rollout.sh; then
  echo "배포 실패: Kafka 브로커 처리 중 오류가 발생했다" >&2
  exit 1
fi

# nginx가 app healthy를 기다리므로 전체를 한 번에 up -d 하면 app이 비정상일 때 로그를 찍기 전에 실패한다.
# 그래서 app(과 mysql)만 먼저 올리고 healthy를 확인한 뒤 bff, 나머지 순서로 올린다
# 서비스마다 healthy가 되면 바로 이력을 남긴다. app 성공 뒤 bff가 실패해도 app의 새 정상 이력은 남는다
if ! start_pulled app; then
  docker compose logs --tail 100 mysql >&2
  fail_hint app
fi

# 여기서 실패해도 이미 떠 있는 nginx와 /api/v1/(API_UPSTREAM 기본값일 때)은 새 app으로 계속 응답한다
start_pulled bff || fail_hint bff

# APP_IMAGE_REF·BFF_IMAGE_REF가 위 digest로 채워져 있어 app·bff는 다시 만들지 않는다
if ! docker compose up -d; then
  echo "배포 실패: app·bff는 healthy지만 나머지 서비스 기동에 실패했다" >&2
  exit 1
fi
echo "배포 성공: 전체"
