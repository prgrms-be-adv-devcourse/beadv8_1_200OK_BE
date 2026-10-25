#!/bin/bash
# 모듈별 스키마(MySQL 데이터베이스)를 만들고 앱 계정에 권한을 준다.
# MySQL 컨테이너가 빈 데이터 디렉터리로 처음 뜰 때만 실행된다(/docker-entrypoint-initdb.d).
# 이미 데이터가 있는 DB는 이 스크립트가 돌지 않으므로 infra/mysql/migration-288의 이전 절차를 쓴다. 로컬(infra/mysql/compose.yml)과 운영(deploy/compose.prod.yml)이 이 파일을 함께 쓴다.
# 스키마 이름은 각 모듈의 XxxSchema.NAME과 같아야 한다.
set -euo pipefail

for module in member wallet order product delivery payout media refund; do
  mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" -e "
    CREATE DATABASE IF NOT EXISTS \`paldogotgan_${module}\` DEFAULT CHARACTER SET utf8mb4;
    GRANT ALL PRIVILEGES ON \`paldogotgan_${module}\`.* TO '${MYSQL_USER}'@'%';"
done
