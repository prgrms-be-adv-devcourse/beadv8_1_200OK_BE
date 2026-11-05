#!/usr/bin/env bash
# Redis 노드 6대를 서비스가 끊기지 않게 띄우거나 다시 띄운다 (#366). deploy.sh가 app보다 먼저 호출한다
#
# 왜 따로 하나: deploy.sh 마지막의 docker compose up -d는 설정이 바뀐 서비스를 모두 동시에 다시 만든다
#   REDIS_VERSION을 올리거나 Redis 설정을 바꾼 배포에서 6대가 함께 내려가 그 사이 Redis 전체가 멈춘다
#   1대씩 다시 띄우면 나머지가 계속 동작한다. 마스터는 복제본에 역할을 넘긴 뒤(CLUSTER FAILOVER) 내려, 쓰기가 끊기지 않게 한다
# 상태별 처리:
#   [a] 6대 모두 떠 있지 않음(첫 배포, down 이후, 단일 Redis에서 처음 전환) → 옛 단일 redis 컨테이너를 먼저 지우고 6대를 띄운 뒤 클러스터로 묶는다
#       옛 컨테이너(384m)와 새 노드 6대(약 1GiB)가 같이 떠 있으면 EC2 메모리 예산을 넘을 수 있어 옛 것을 먼저 지운다
#   [b] 6대 모두 healthy → 1대씩 처리한다. 복제본을 먼저, 마스터는 그 복제본에 역할을 넘긴 뒤 다시 띄운다
#       노드마다 healthy, 복제 연결, cluster_state:ok를 기다린 뒤 다음 노드로 간다. 설정이 그대로인 노드는 건드리지 않는다
#       배포 시작 시점에 복제본이 이미 마스터로 승격돼 있어도 정상이다. 원래 짝으로 되돌리지 않고 지금 역할대로 처리한다
#   [c] 일부만 살아 있거나, 복제본이 없는 마스터가 있음 → 중단한다. 이 상태에서 1대를 더 내리면 슬롯이 비거나 승격할 복제본이 없다
#   켜지는 중(starting)인 노드는 판정 전에 healthy가 될 때까지 기다린다
# 로컬 검증: COMPOSE_FILE=../docker-compose.yml ./redis-rollout.sh (로컬 compose도 같은 서비스 이름을 쓴다)
set -euo pipefail
cd "$(dirname "$0")"

export COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yml}"

# 두 번 동시에 돌면 서로 다른 노드를 함께 내려 클러스터가 멈출 수 있어 한 번에 하나만 실행한다
# deploy.sh의 잠금, kafka-rollout.sh의 잠금과 다른 파일을 쓴다. deploy.sh가 잠근 채 이 스크립트를 부르므로 같은 파일이면 스스로 막힌다
# flock이 없는 곳(로컬 검증)에서는 잠그지 않는다
if command -v flock > /dev/null; then
  exec 7>/tmp/paldo-redis-rollout.lock
  flock -n 7 || { echo "Redis 재시작이 이미 진행 중이다" >&2; exit 1; }
fi

NODES=(redis-1 redis-2 redis-3 redis-4 redis-5 redis-6)
RETRIES=24    # 5초 × 24 = 최대 2분 (deploy.sh의 app 대기와 같은 기준)
INTERVAL=5
# 역할을 넘긴 직후 앱(Lettuce)이 새 마스터를 따라갈 시간. 리다이렉트 때 바로 토폴로지를 다시 읽으므로 짧게 둔다
SETTLE=5

# 노드 안에서 redis-cli를 실행한다. 운영은 6379와 REDISCLI_AUTH, 로컬은 NODE_PORT를 쓰므로 컨테이너 안의 환경변수를 따른다
rcli() {
  local node=$1
  shift
  docker compose exec -T "$node" sh -c 'exec redis-cli -p "${NODE_PORT:-6379}" "$@"' sh "$@"
}

# 노드의 상태를 한 단어로 돌려준다: healthy / starting / unhealthy / stopped / none / error
# docker 조회가 실패했을 때 none(컨테이너 없음)으로 보면 "6대 동시 기동" 경로로 빠져,
# 설정이 바뀐 배포에서 막으려던 동시 재시작이 일어난다. 그래서 조회 실패는 error로 따로 돌려준다
node_status() {
  local cid
  if ! cid=$(docker compose ps -a -q "$1"); then
    echo error
    return
  fi
  if [ -z "$cid" ]; then
    echo none
    return
  fi
  docker inspect -f '{{if .State.Running}}{{.State.Health.Status}}{{else}}stopped{{end}}' "$cid" || echo error
}

wait_healthy() {
  local node=$1 status=none
  for _ in $(seq 1 "$RETRIES"); do
    status=$(node_status "$node")
    if [ "$status" = healthy ]; then
      return 0
    fi
    sleep "$INTERVAL"
  done
  echo "Redis 배포 실패: $node 이(가) 2분 안에 healthy가 되지 않았다 (마지막 상태: $status)" >&2
  docker compose logs --tail 50 "$node" >&2
  return 1
}

# 노드의 현재 역할: master / slave
node_role() {
  rcli "$1" role | head -n 1 | tr -d '\r'
}

# 6대가 모두 cluster_state:ok이고 복제본이 마스터에 붙어 있을 때까지 기다린다
wait_cluster_ok() {
  local when=$1 detail=""
  for _ in $(seq 1 "$RETRIES"); do
    detail=""
    for node in "${NODES[@]}"; do
      if ! rcli "$node" cluster info 2>/dev/null | tr -d '\r' | grep -q '^cluster_state:ok$'; then
        detail="$detail $node(cluster_state 아님)"
      elif [ "$(node_role "$node")" = slave ] \
        && ! rcli "$node" info replication 2>/dev/null | tr -d '\r' | grep -q '^master_link_status:up$'; then
        detail="$detail $node(복제 연결 안 됨)"
      fi
    done
    [ -z "$detail" ] && return 0
    sleep "$INTERVAL"
  done
  echo "Redis 배포 실패: $when 2분 안에 클러스터가 정상이 되지 않았다:$detail" >&2
  return 1
}

# 마스터 노드 이름에 붙은 복제본 노드 이름을 돌려준다. 없으면 빈 값이다
# cluster nodes의 행: <id> <ip:port@버스포트,호스트이름> <flags> <마스터 id> ... 이다. 호스트이름의 .localhost(로컬)는 서비스 이름이 아니라 뗀다
replica_of() {
  local master=$1 master_id
  master_id=$(rcli "$master" cluster myid | tr -d '\r')
  rcli "$master" cluster nodes | tr -d '\r' \
    | awk -v id="$master_id" '$3 ~ /slave/ && $3 !~ /fail/ && $4 == id { split($2, a, ","); print a[2]; exit }' \
    | sed 's/\.localhost$//'
}

# 이 노드를 지금 설정대로 다시 만들어야 하는지(이미지·설정이 바뀌었는지). 바뀐 것이 없으면 역할을 넘길 필요가 없다
needs_recreate() {
  local out
  out=$(docker compose --dry-run up -d --no-deps "$1" 2>&1) || return 0
  case "$out" in
    *Recreate*) return 0 ;;
    *) return 1 ;;
  esac
}

# 켜지는 중인 노드가 있으면 healthy가 될 때까지 기다린 뒤 판정한다 (재부팅 직후 오판 방지)
for node in "${NODES[@]}"; do
  if [ "$(node_status "$node")" = starting ]; then
    echo "Redis: $node 이(가) 켜지는 중이라 healthy가 될 때까지 기다린다"
    wait_healthy "$node"
  fi
done

healthy_count=0
up_count=0
for node in "${NODES[@]}"; do
  status=$(node_status "$node")
  if [ "$status" = error ]; then
    echo "Redis 배포 중단: $node 상태를 조회하지 못했다. docker 상태를 확인한 뒤 다시 배포한다" >&2
    exit 1
  fi
  [ "$status" = healthy ] && healthy_count=$((healthy_count + 1))
  [ "$status" != none ] && [ "$status" != stopped ] && up_count=$((up_count + 1))
done

# 새 이미지(REDIS_VERSION 변경)를 노드를 건드리기 전에 미리 받아, 받기가 실패하면 아무것도 내리지 않은 채 멈춘다
# 로컬에 이미 있는 이미지는 레지스트리에 묻지 않는다(--policy missing). 버전 태그를 고정해 쓰므로 다시 받을 일이 없다
docker compose pull --quiet --policy missing "${NODES[@]}"

if [ "$up_count" -eq 0 ]; then
  # [a] 옛 단일 redis 컨테이너가 있으면 먼저 지운다. 프로젝트 이름은 compose가 실제로 쓰는 값을 읽는다
  project=$(docker compose config | sed -n 's/^name: //p' | head -n 1)
  old=$(docker ps -aq --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.service=redis")
  if [ -n "$old" ]; then
    echo "Redis: 옛 단일 redis 컨테이너를 지운다 (데이터는 옮기지 않는다)"
    # 컨테이너 ID 목록을 공백으로 나눠 넘긴다
    # shellcheck disable=SC2086
    docker rm -f $old > /dev/null
  fi
  echo "Redis: 떠 있는 노드가 없어 6대를 동시에 띄우고 클러스터로 묶는다"
  docker compose up -d --no-deps "${NODES[@]}"
  # 노드는 클러스터가 만들어지기 전에는 healthy가 될 수 없다(cluster_state:ok). 그래서 healthy 대신 초기화부터 한다
  docker compose run --rm --no-deps redis-cluster-init
  for node in "${NODES[@]}"; do
    wait_healthy "$node"
  done
  wait_cluster_ok "초기화 뒤"
elif [ "$healthy_count" -eq "${#NODES[@]}" ]; then
  # [b] 시작하기 전에 마스터마다 복제본이 있는지 확인한다. 없는 마스터를 내리면 그 슬롯이 멈춘다
  for node in "${NODES[@]}"; do
    if [ "$(node_role "$node")" = master ] && [ -z "$(replica_of "$node")" ]; then
      echo "Redis 배포 중단: 마스터 $node 에 복제본이 없다. 이 마스터를 내리면 슬롯이 멈춘다. 원인을 확인한 뒤 다시 배포한다 (docs/DEPLOY.md)" >&2
      exit 1
    fi
  done
  wait_cluster_ok "재시작 전"

  # 복제본을 먼저 처리한다. 마스터에 역할을 넘길 복제본이 항상 살아 있게 하기 위해서다
  # 역할은 노드마다 지금 읽는다. 앞서 처리한 노드의 장애 조치로 바뀌었을 수 있다
  for pass in slave master; do
    for node in "${NODES[@]}"; do
      [ "$(node_role "$node")" = "$pass" ] || continue
      needs_recreate "$node" || continue
      if [ "$pass" = master ]; then
        replica=$(replica_of "$node")
        echo "Redis: 마스터 $node 의 역할을 복제본 $replica 에 넘긴다"
        rcli "$replica" cluster failover > /dev/null
        for _ in $(seq 1 "$RETRIES"); do
          [ "$(node_role "$replica")" = master ] && break
          sleep 1
        done
        if [ "$(node_role "$replica")" != master ]; then
          echo "Redis 배포 실패: $replica 이(가) 마스터가 되지 않았다. $node 는 건드리지 않았다" >&2
          exit 1
        fi
        sleep "$SETTLE"
      fi
      echo "Redis: $node 을(를) 다시 띄운다"
      docker compose up -d --no-deps "$node"
      wait_healthy "$node"
      wait_cluster_ok "$node 재시작 뒤"
    done
  done
  echo "Redis: 6대 모두 healthy, 복제 연결 정상"
else
  # [c] 일부만 살아 있다. 사람이 먼저 원인을 보고 노드를 살린 뒤 다시 배포한다
  echo "Redis 배포 중단: 노드 6대 중 healthy가 ${healthy_count}대뿐이다. 이 상태에서 다시 띄우면 클러스터가 멈추거나 승격할 복제본이 없다" >&2
  for node in "${NODES[@]}"; do
    echo "  $node: $(node_status "$node")" >&2
  done
  echo "  docker compose ps 와 docker compose logs redis-N 으로 원인을 확인하고 노드를 살린 뒤 다시 배포한다 (docs/DEPLOY.md)" >&2
  exit 1
fi
