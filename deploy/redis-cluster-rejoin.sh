#!/bin/sh
# Redis 노드가 재시작으로 IP가 바뀌어도 클러스터에 스스로 다시 붙게 한다 (#366)
# 각 노드 컨테이너가 redis-server와 함께 백그라운드로 실행한다. redis alpine 이미지에는 bash가 없어서 POSIX sh로 쓴다
#
# 왜 필요한가: 노드끼리는 이름이 아니라 nodes.conf에 적힌 IP로 서로를 찾는다 (cluster-announce-hostname은 앱에게 알려 주는 주소일 뿐이다)
#   6대가 모두 새 IP로 올라오면 서로를 못 찾아 CLUSTERDOWN이 풀리지 않는다. 그래서 피어 이름을 DNS로 풀어 현재 IP로 CLUSTER MEET을 보낸다
#   MEET 한 번이면 가십으로 나머지 노드도 새 주소를 알게 된다. IP를 고정하지 않으므로 노드를 늘려도 REDIS_PEERS만 늘리면 된다
#
# 입력: REDIS_PEERS  공백으로 구분한 host:port 목록 (자기 자신이 들어 있어도 건너뛴다)
#       NODE_PORT    이 노드의 포트 (기본 6379)
#       REDISCLI_AUTH  있으면 redis-cli가 인증에 쓴다 (운영)
#
# 처음 클러스터를 만들기 전에는 아무것도 하지 않는다 (cluster_known_nodes=1)
#   MEET으로 노드가 서로 알게 되면 redis-cli --cluster create가 "비어 있지 않은 노드"라며 거부하므로, 만드는 일은 redis-cluster-init에 맡긴다
set -u

: "${REDIS_PEERS:?REDIS_PEERS가 필요하다}"
PORT="${NODE_PORT:-6379}"
INTERVAL=5

cli() { redis-cli -p "$PORT" "$@" 2>/dev/null | tr -d '\r'; }

# redis-server가 뜰 때까지 기다린다
until [ "$(cli ping)" = PONG ]; do sleep 1; done

my_ip=$(hostname -i | cut -d' ' -f1)

while :; do
  known=$(cli cluster info | sed -n 's/^cluster_known_nodes://p')
  if [ "${known:-1}" -gt 1 ]; then
    nodes=$(cli cluster nodes)
    for peer in $REDIS_PEERS; do
      host="${peer%:*}"
      peer_port="${peer##*:}"
      # CLUSTER MEET은 이름이 아니라 IP만 받는다. IPv4만 쓴다
      ip=$(getent ahostsv4 "$host" 2>/dev/null | awk 'NR==1 {print $1}')
      [ -n "$ip" ] || continue
      [ "$ip" != "$my_ip" ] || continue
      # 피어 컨테이너가 멈춰 도커 DNS에 이름이 없으면 *.localhost(로컬 이름)가 루프백으로 풀린다. 그 주소는 피어가 아니므로 건너뛴다
      case "$ip" in 127.*) continue ;; esac
      # 이미 그 노드를 그 주소로 알고 있으면 다시 보내지 않는다. 비교는 cluster nodes의 "IP:포트@버스포트,이름" 전체로 한다
      # IP나 포트만 비교하면 안 된다. 재시작으로 IP가 서로 자리를 바꾸면 옛 목록의 같은 IP가 다른 노드의 것이라 "이미 안다"로 오판한다
      # 운영은 노드 포트가 모두 6379라서 포트만으로도 구분되지 않는다
      echo "$nodes" | grep -q " $ip:$peer_port@[0-9]*,$host " && continue
      echo "rejoin: $host 의 현재 주소 $ip:$peer_port 로 CLUSTER MEET"
      cli cluster meet "$ip" "$peer_port" >/dev/null
    done
  fi
  sleep "$INTERVAL"
done
