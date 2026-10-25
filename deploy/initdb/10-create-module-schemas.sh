#!/bin/bash
# 모듈별 스키마(MySQL 데이터베이스)를 만들고 앱 계정에 권한을 준다.
# MySQL 컨테이너가 빈 데이터 디렉터리로 처음 뜰 때만 실행된다(/docker-entrypoint-initdb.d).
# 이미 데이터가 있는 DB는 이 스크립트가 돌지 않으므로 infra/mysql/migration-288의 이전 절차를 쓴다. 로컬(infra/mysql/compose.yml)과 운영(deploy/compose.prod.yml)이 이 파일을 함께 쓴다.
# 스키마 이름은 각 모듈의 XxxSchema.NAME과 같아야 한다.
set -euo pipefail

# 사용자 이름을 SQL에 그대로 끼워 넣으므로 식별자 모양이 아니면 멈춘다
if ! [[ "${MYSQL_USER:-}" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "MYSQL_USER가 비었거나 허용하지 않는 문자를 포함한다" >&2
  exit 1
fi

# 비밀번호를 명령행 인자로 넘기지 않는다
export MYSQL_PWD="${MYSQL_ROOT_PASSWORD}"

for module in member wallet order product delivery payout media refund; do
  mysql -uroot -e "
    CREATE DATABASE IF NOT EXISTS \`paldogotgan_${module}\` DEFAULT CHARACTER SET utf8mb4;
    GRANT ALL PRIVILEGES ON \`paldogotgan_${module}\`.* TO '${MYSQL_USER}'@'%';"
done
