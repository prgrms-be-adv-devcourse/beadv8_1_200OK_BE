#!/bin/sh
# Redis 노드 6대를 클러스터(마스터 3 + 복제본 3)로 묶는다 (#366). 로컬 docker-compose.yml과 운영 compose.prod.yml이 같이 쓴다
# redis alpine 이미지에는 bash가 없어서 POSIX sh로 쓴다
#
# 입력: REDIS_NODES  공백으로 구분한 host:port 6개
#       REDISCLI_AUTH  있으면 redis-cli가 알아서 인증에 쓴다 (운영). 비어 있으면 비밀번호 없이 접속한다 (로컬)
#
# 상태별 처리 (이 서비스는 배포 때마다 다시 실행되므로, 이미 구성된 클러스터를 건드리지 않는 것이 핵심이다)
#   [a] 6대 모두 아무도 모름(cluster_known_nodes=1) → 새 클러스터를 만든다
#   [b] 6대 모두 서로 아는 상태(cluster_known_nodes=6) → 만들지 않고, cluster_state:ok가 될 때까지만 기다린다
#   [c] 그 밖(일부 노드만 볼륨을 잃었거나, 일부만 묶여 있음) → 실패한다
#       이 상태에서 create를 다시 하면 이미 쌓인 데이터와 슬롯 배치에 영향을 줄 수 있어, 사람이 원인을 보고 정한다
set -eu

: "${REDIS_NODES:?REDIS_NODES가 필요하다}"

WAIT_SECONDS=120

host_of() { echo "${1%:*}"; }
port_of() { echo "${1##*:}"; }

# 노드의 cluster info에서 key의 값을 돌려준다. 응답이 없으면 빈 값이다
info_value() {
  redis-cli -h "$(host_of "$1")" -p "$(port_of "$1")" cluster info 2>/dev/null | tr -d '\r' | sed -n "s/^$2://p"
}

# 노드가 늦게 뜨는 경우가 있어, 6대 모두 응답할 때까지 기다린다
wait_all_responding() {
  waited=0
  for node in $REDIS_NODES; do
    until [ "$(redis-cli -h "$(host_of "$node")" -p "$(port_of "$node")" ping 2>/dev/null | tr -d '\r')" = PONG ]; do
      if [ "$waited" -ge "$WAIT_SECONDS" ]; then
        echo "Redis 클러스터 초기화 실패: $node 이(가) ${WAIT_SECONDS}초 안에 응답하지 않았다" >&2
        exit 1
      fi
      sleep 2
      waited=$((waited + 2))
    done
  done
}

# 6대 모두 cluster_state:ok이고 서로 6대를 알 때까지 기다린다
wait_cluster_ok() {
  waited=0
  while :; do
    ok=1
    for node in $REDIS_NODES; do
      [ "$(info_value "$node" cluster_state)" = ok ] && [ "$(info_value "$node" cluster_known_nodes)" = 6 ] || ok=0
    done
    [ "$ok" = 1 ] && return 0
    if [ "$waited" -ge "$WAIT_SECONDS" ]; then
      echo "Redis 클러스터 초기화 실패: ${WAIT_SECONDS}초 안에 6대 모두 cluster_state:ok가 되지 않았다" >&2
      for node in $REDIS_NODES; do
        echo "  $node state=$(info_value "$node" cluster_state) known_nodes=$(info_value "$node" cluster_known_nodes)" >&2
      done
      exit 1
    fi
    sleep 2
    waited=$((waited + 2))
  done
}

count=0
for node in $REDIS_NODES; do count=$((count + 1)); done
if [ "$count" -ne 6 ]; then
  echo "Redis 클러스터 초기화 실패: REDIS_NODES는 6개여야 하는데 ${count}개다" >&2
  exit 1
fi

wait_all_responding

fresh=0
joined=0
for node in $REDIS_NODES; do
  known=$(info_value "$node" cluster_known_nodes)
  [ "$known" = 1 ] && fresh=$((fresh + 1))
  [ "$known" = 6 ] && joined=$((joined + 1))
done

if [ "$fresh" -eq 6 ]; then
  echo "새 클러스터를 만든다 (마스터 3 + 복제본 3)"
  # 노드 목록을 공백으로 나눠 넘긴다
  # shellcheck disable=SC2086
  redis-cli --cluster create $REDIS_NODES --cluster-replicas 1 --cluster-yes
  wait_cluster_ok
  echo "클러스터 구성 완료"
elif [ "$joined" -eq 6 ]; then
  echo "이미 구성된 클러스터라 만들지 않는다"
  wait_cluster_ok
  echo "클러스터 상태 정상"
else
  echo "Redis 클러스터 초기화 실패: 노드 상태가 섞여 있어 create를 하지 않는다 (일부 노드의 볼륨이 사라졌거나 일부만 묶여 있음)" >&2
  for node in $REDIS_NODES; do
    echo "  $node known_nodes=$(info_value "$node" cluster_known_nodes)" >&2
  done
  echo "원인을 확인한 뒤, 데이터를 버려도 되면 6대의 볼륨을 모두 지우고 다시 시작한다 (docs/DEPLOY.md)" >&2
  exit 1
fi
