#!/usr/bin/env bash
# Kafka 브로커 3대를 클러스터가 멈추지 않게 띄우거나 다시 띄운다 (#318). deploy.sh가 app보다 먼저 호출한다
#
# 왜 따로 하나: deploy.sh 마지막의 docker compose up -d는 설정이 바뀐 서비스를 모두 동시에 다시 만든다
#   KAFKA_VERSION을 올리거나 Kafka 설정을 바꾼 배포에서 3대가 함께 내려가 그 사이 Kafka 전체가 멈춘다
#   1대씩 다시 띄우면 나머지 2대가 계속 동작한다 (min.insync.replicas=2)
# 상태별 처리:
#   [a] 3대 모두 떠 있지 않음(첫 배포, down 이후) → 3대를 동시에 띄운다
#       KRaft는 3대 중 2대가 모여야 시작하므로, 1대씩 띄우고 기다리면 혼자서는 시작할 수 없어 끝없이 기다린다
#   [b] 3대 모두 healthy → 복사가 다 따라잡혔는지 먼저 확인 → 1대씩 up -d → healthy와 복사 따라잡기를 기다린 뒤 다음 브로커
#       설정이 그대로인 브로커는 compose가 다시 만들지 않아 평소 배포에서는 바로 지나간다
#   [c] 일부만 살아 있음 → 중단한다. 이 상태에서 1대를 더 내리면 살아 있는 브로커가 1대뿐이라 Kafka 전체가 멈춘다
#   켜지는 중(starting)인 브로커는 판정 전에 healthy가 될 때까지 기다린다
# 로컬 검증: COMPOSE_FILE=../docker-compose.yml ./kafka-rollout.sh (로컬 compose도 같은 서비스 이름을 쓴다)
set -euo pipefail
cd "$(dirname "$0")"

export COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yml}"

# 두 번 동시에 돌면 서로 다른 브로커를 함께 내려 클러스터가 멈출 수 있어 한 번에 하나만 실행한다
# deploy.sh의 잠금과 다른 파일을 쓴다. deploy.sh가 잠근 채 이 스크립트를 부르므로 같은 파일이면 스스로 막힌다
# flock이 없는 곳(Windows 로컬 검증)에서는 잠그지 않는다
if command -v flock > /dev/null; then
  exec 8>/tmp/paldo-kafka-rollout.lock
  flock -n 8 || { echo "Kafka 재시작이 이미 진행 중이다" >&2; exit 1; }
fi

BROKERS=(kafka-1 kafka-2 kafka-3)
RETRIES=24   # 5초 × 24 = 최대 2분 (deploy.sh의 app 대기와 같은 기준)
INTERVAL=5

# 서비스의 상태를 한 단어로 돌려준다: healthy / starting / unhealthy / stopped / none / error
# docker 조회가 실패했을 때 none(컨테이너 없음)으로 보면 "3대 동시 기동" 경로로 빠져,
# 설정이 바뀐 배포에서 막으려던 3대 동시 재시작이 일어난다. 그래서 조회 실패는 error로 따로 돌려준다
broker_status() {
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
  local broker=$1 status=none
  for _ in $(seq 1 "$RETRIES"); do
    status=$(broker_status "$broker")
    if [ "$status" = healthy ]; then
      return 0
    fi
    sleep "$INTERVAL"
  done
  echo "Kafka 배포 실패: $broker 이(가) 2분 안에 healthy가 되지 않았다 (마지막 상태: $status)" >&2
  docker compose logs --tail 50 "$broker" >&2
  return 1
}

# healthy는 "요청을 받기 시작함"일 뿐이다. 밀린 복사본을 다 따라잡기 전에 다음 브로커를 내리면
# 잠깐 복사본이 1벌뿐인 파티션이 생기므로, 복사가 덜 된 파티션이 0개가 될 때까지 기다린다
# 리더가 없어 읽고 쓸 수 없는 파티션은 복사가 덜 된 파티션 목록에 나오지 않을 수 있어 함께 확인한다
# 두 번째 인자는 실패 메시지에 넣을 시점이다 (재시작 전 / 재시작 뒤)
wait_in_sync() {
  local broker=$1 when=$2 lagging="" errlog
  # 조회가 실패했을 때 원인(접속 오류, 메모리 부족 등)을 실패 메시지에 남기려고 오류 출력을 따로 받는다
  errlog=$(mktemp)
  for _ in $(seq 1 "$RETRIES"); do
    # 이 명령도 JVM을 띄우므로 힙을 작게 묶는다
    lagging=$(docker compose exec -T "$broker" sh -c \
      'KAFKA_HEAP_OPTS=-Xmx64m /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --under-replicated-partitions &&
       KAFKA_HEAP_OPTS=-Xmx64m /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --unavailable-partitions' 2>"$errlog") \
      || lagging="조회 실패: $(tail -n 5 "$errlog")"
    if [ -z "$lagging" ]; then
      rm -f "$errlog"
      return 0
    fi
    sleep "$INTERVAL"
  done
  rm -f "$errlog"
  echo "Kafka 배포 실패: $broker $when 2분 안에 복사가 다 따라잡히지 않았다" >&2
  echo "$lagging" >&2
  return 1
}

# 서버 재부팅 직후처럼 아직 켜지는 중(starting)인 브로커가 있으면, 바로 판정하면 "일부만 살아 있음"으로 잘못 보고 배포가 멈춘다
# 켜지는 중인 브로커는 healthy가 될 때까지 기다린 뒤 판정한다
for broker in "${BROKERS[@]}"; do
  if [ "$(broker_status "$broker")" = starting ]; then
    echo "Kafka: $broker 이(가) 켜지는 중이라 healthy가 될 때까지 기다린다"
    wait_healthy "$broker"
  fi
done

healthy_count=0
up_count=0
for broker in "${BROKERS[@]}"; do
  status=$(broker_status "$broker")
  if [ "$status" = error ]; then
    echo "Kafka 배포 중단: $broker 상태를 조회하지 못했다. docker 상태를 확인한 뒤 다시 배포한다" >&2
    exit 1
  fi
  [ "$status" = healthy ] && healthy_count=$((healthy_count + 1))
  [ "$status" != none ] && [ "$status" != stopped ] && up_count=$((up_count + 1))
done

# 새 이미지(KAFKA_VERSION 변경)를 브로커를 건드리기 전에 미리 받아, 받기가 실패하면 아무것도 내리지 않은 채 멈춘다
# 로컬에 이미 있는 이미지는 레지스트리에 묻지 않는다(--policy missing). 버전 태그를 고정해 쓰므로 다시 받을 일이 없고,
# 묻게 두면 Docker Hub 장애 때 Kafka가 바뀌지 않는 배포까지 실패한다
docker compose pull --quiet --policy missing "${BROKERS[@]}"

if [ "$up_count" -eq 0 ]; then
  # [a] 3대를 동시에 띄운다. 브로커끼리 depends_on이 없어 compose가 한꺼번에 시작한다
  echo "Kafka: 떠 있는 브로커가 없어 3대를 동시에 띄운다"
  docker compose up -d --no-deps "${BROKERS[@]}"
  for broker in "${BROKERS[@]}"; do
    wait_healthy "$broker"
  done
elif [ "$healthy_count" -eq "${#BROKERS[@]}" ]; then
  # [b] healthy는 복사를 다 따라잡았다는 뜻이 아니다. 직전에 다시 켜진 브로커가 아직 따라잡는 중이면
  #     최신 복사본을 가진 브로커가 2대뿐인 파티션이 있어, 첫 브로커를 내리는 순간 min.insync.replicas=2를 못 채워 발행이 실패한다
  #     그래서 첫 재시작 전에도 확인한다. 클러스터 전체를 조회하므로 한 대에서만 물으면 된다
  wait_in_sync "${BROKERS[0]}" "재시작 전"
  # 1대씩 다시 띄운다 (바뀐 것이 없으면 compose가 그대로 둔다)
  for broker in "${BROKERS[@]}"; do
    docker compose up -d --no-deps "$broker"
    wait_healthy "$broker"
    wait_in_sync "$broker" "재시작 뒤"
  done
  echo "Kafka: 3대 모두 healthy, 복사 따라잡음"
else
  # [c] 일부만 살아 있다. 사람이 먼저 원인을 보고 브로커를 살린 뒤 다시 배포한다
  echo "Kafka 배포 중단: 브로커 3대 중 healthy가 ${healthy_count}대뿐이다. 이 상태에서 다시 띄우면 클러스터가 멈출 수 있다" >&2
  for broker in "${BROKERS[@]}"; do
    echo "  $broker: $(broker_status "$broker")" >&2
  done
  echo "  docker compose ps 와 docker compose logs kafka-N 으로 원인을 확인하고 브로커를 살린 뒤 다시 배포한다 (docs/DEPLOY.md)" >&2
  exit 1
fi
