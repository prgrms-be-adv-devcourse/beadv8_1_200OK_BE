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

# Kafka 구성은 프로파일로 고른다 (#530). single(브로커 1대)과 cluster(브로커 3대) 중 하나만 켠다
# compose도 .env의 COMPOSE_PROFILES를 읽지만, 아래에서 어느 구성인지 알아야 해서 같은 값을 직접 구한다
# 셸 환경에 있으면 그 값을, 없으면 .env의 값을, 둘 다 없으면 single이다
if [ -z "${COMPOSE_PROFILES:-}" ] && [ -f .env ]; then
  COMPOSE_PROFILES=$(grep -E '^COMPOSE_PROFILES=' .env | tail -1 | cut -d= -f2- | tr -d '"' || true)
fi
export COMPOSE_PROFILES="${COMPOSE_PROFILES:-single}"

HAS_SINGLE=0
HAS_CLUSTER=0
case ",${COMPOSE_PROFILES}," in *,single,*) HAS_SINGLE=1 ;; esac
case ",${COMPOSE_PROFILES}," in *,cluster,*) HAS_CLUSTER=1 ;; esac
if [ "$HAS_SINGLE" = 1 ] && [ "$HAS_CLUSTER" = 1 ]; then
  echo "COMPOSE_PROFILES에 single과 cluster를 함께 쓸 수 없다 (Kafka가 두 벌 뜬다): $COMPOSE_PROFILES" >&2
  exit 1
fi
if [ "$HAS_SINGLE" = 0 ] && [ "$HAS_CLUSTER" = 0 ]; then
  echo "COMPOSE_PROFILES에 single이나 cluster가 필요하다 (Kafka가 뜨지 않는다): $COMPOSE_PROFILES" >&2
  exit 1
fi

docker compose pull app

if [ "$HAS_CLUSTER" = 1 ]; then
  # 클러스터는 app보다 먼저, 멈추지 않게 1대씩 처리한다 (#318)
  # 아래 up -d app이 kafka의 depends_on을 따라 3대를 한꺼번에 다시 만들지 않도록, 여기서 먼저 최신 상태로 맞춰 둔다
  if ! ./kafka-rollout.sh; then
    echo "배포 실패: Kafka 브로커 처리 중 오류가 발생했다" >&2
    exit 1
  fi
else
  # 3대 클러스터로 돌던 서버가 single로 바뀌면(.env를 바꿨거나, 값이 없어 기본값 single이 된 경우) 꺼진 프로파일의 브로커는 compose가 멈추지 않아
  # 계속 떠서 메모리를 쓰고, 앱은 새 단일 브로커로 붙어 두 벌이 따로 돈다. 조용히 구성이 바뀌지 않도록 여기서 멈춘다
  if [ -n "$(docker compose --profile cluster ps -q kafka-1 kafka-2 kafka-3 2>/dev/null)" ]; then
    echo "배포 중단: 클러스터 브로커(kafka-1~3)가 아직 떠 있다. 클러스터를 계속 쓰려면 .env에 COMPOSE_PROFILES=cluster 를," >&2
    echo "single로 바꾸려면 docs/DEPLOY.md의 '3대 클러스터로 돌던 서버를 single로 바꿀 때' 절차로 기존 브로커를 먼저 멈춘다" >&2
    exit 1
  fi
  # 단일 브로커는 넘겨받을 상대가 없어, 설정이나 버전이 바뀌면 다시 만드는 동안 Kafka가 잠시 멈춘다
  # 브로커가 healthy가 될 때까지 기다린 뒤 app을 올린다
  if ! docker compose up -d --wait --wait-timeout 180 kafka; then
    echo "배포 실패: Kafka 브로커가 healthy가 되지 않았다" >&2
    docker compose logs --tail 100 kafka >&2
    exit 1
  fi
fi

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
