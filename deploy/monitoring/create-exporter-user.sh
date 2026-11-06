#!/usr/bin/env bash
# mysqld-exporter가 쓰는 MySQL 조회 전용 계정(exporter)을 만들거나 비밀번호를 맞춘다 (#374)
# 몇 번을 실행해도 같은 결과다(멱등). 실행 시점: 모니터링을 처음 켤 때 한 번, MYSQL_EXPORTER_PASSWORD를 바꿀 때
#
# 왜 initdb에 넣지 않나: initdb는 빈 데이터 디렉터리에서 처음 뜰 때만 돌아서, 이미 운영 중인 DB에는 실행되지 않는다
# 사용법
#   운영(서버 /opt/paldo-gotgan에서): monitoring/create-exporter-user.sh
#   로컬(저장소 루트에서):           COMPOSE_FILE=../docker-compose.yml deploy/monitoring/create-exporter-user.sh
#   COMPOSE_FILE은 deploy.sh와 같은 관례이고, 상대 경로는 이 스크립트의 상위 폴더(deploy)를 기준으로 한다
# 전제: mysql과 mysqld-exporter 컨테이너가 떠 있어야 한다 (exporter는 계정이 없어도 떠 있고 mysql_up만 0이다)
set -euo pipefail
cd "$(dirname "$0")/.."

export COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yml}"

# 비밀번호는 명령행에 쓰지 않는다. exporter 컨테이너가 이미 받은 값을 거기서 읽어, 두 곳의 값이 항상 같게 한다
PASSWORD=$(docker compose exec -T mysqld-exporter sh -c 'printf %s "$MYSQL_EXPORTER_PASSWORD"')

# SQL 문자열과 my.cnf 파일에 그대로 들어가므로 따옴표·백슬래시 같은 문자는 받지 않는다
if ! [[ "$PASSWORD" =~ ^[A-Za-z0-9+/=_-]{8,}$ ]]; then
  echo "MYSQL_EXPORTER_PASSWORD는 영문·숫자·+/=_- 로 된 8자 이상이어야 한다" >&2
  exit 1
fi

# SQL은 표준 입력으로 넘겨 ps에 보이지 않게 하고, root 비밀번호는 컨테이너 안의 MYSQL_ROOT_PASSWORD를 쓴다
# '%'는 compose 네트워크(외부에서 닿지 않음)의 exporter 컨테이너 주소가 매번 달라지기 때문이다
# MAX_USER_CONNECTIONS 3: exporter가 커넥션을 몰아 써서 앱의 커넥션 상한(max_connections)을 잠식하지 않게 한다
# 권한은 상태 조회(PROCESS, REPLICATION CLIENT)와 performance_schema 조회뿐이라 데이터 스키마는 읽지 못한다
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot' <<SQL
CREATE USER IF NOT EXISTS 'exporter'@'%' IDENTIFIED BY '${PASSWORD}' WITH MAX_USER_CONNECTIONS 3;
ALTER USER 'exporter'@'%' IDENTIFIED BY '${PASSWORD}' WITH MAX_USER_CONNECTIONS 3;
GRANT PROCESS, REPLICATION CLIENT ON *.* TO 'exporter'@'%';
GRANT SELECT ON performance_schema.* TO 'exporter'@'%';
SQL

echo "exporter 계정을 맞췄다"
