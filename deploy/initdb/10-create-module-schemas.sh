#!/bin/bash
# 모듈별 스키마(MySQL 데이터베이스)를 만들고 앱 계정에 권한을 준다.
# MySQL 컨테이너가 빈 데이터 디렉터리로 처음 뜰 때만 실행된다(/docker-entrypoint-initdb.d).
# 이미 데이터가 있는 DB는 이 스크립트가 돌지 않으므로 infra/mysql/migration-288의 이전 절차를 쓴다. 로컬(infra/mysql/compose.yml)과 운영(deploy/compose.prod.yml)이 이 파일을 함께 쓴다.
# 스키마 이름은 paldogotgan_<모듈 이름>이고 각 모듈의 XxxSchema.NAME과 같아야 한다.
# 모듈 목록은 같은 폴더의 modules.txt에서 읽는다. 그 파일은 settings.gradle.kts의 모듈에서 ./gradlew generateInitDb가 만든다(직접 고치지 않는다).
# GRANT의 DB 이름에서 `_`는 한 글자 와일드카드이므로 `\_`로 이스케이프한다(안 하면 paldogotganXmember 같은 다른 DB에도 권한이 간다).
set -euo pipefail

# 사용자 이름을 SQL에 그대로 끼워 넣으므로 식별자 모양이 아니면 멈춘다
if ! [[ "${MYSQL_USER:-}" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "MYSQL_USER가 비었거나 허용하지 않는 문자를 포함한다" >&2
  exit 1
fi

# 비밀번호를 명령행 인자로 넘기지 않는다
export MYSQL_PWD="${MYSQL_ROOT_PASSWORD}"

# 파일이 source로 실행될 수도 있어 $0 대신 BASH_SOURCE를 쓴다
MODULES_FILE="$(dirname "${BASH_SOURCE[0]}")/modules.txt"
if [[ ! -f "${MODULES_FILE}" ]]; then
  echo "모듈 목록 파일이 없다: ${MODULES_FILE} (./gradlew generateInitDb)" >&2
  exit 1
fi

# 모듈 이름을 SQL에 그대로 끼워 넣으므로 소문자 영문이 아니면 멈춘다
modules=()
while IFS= read -r line || [[ -n "${line}" ]]; do
  line="${line//[[:space:]]/}"
  [[ -z "${line}" || "${line}" == \#* ]] && continue
  if ! [[ "${line}" =~ ^[a-z]+$ ]]; then
    echo "modules.txt에 허용하지 않는 모듈 이름이 있다: ${line}" >&2
    exit 1
  fi
  modules+=("${line}")
done < "${MODULES_FILE}"

if [[ ${#modules[@]} -eq 0 ]]; then
  echo "modules.txt에 모듈이 없다" >&2
  exit 1
fi

for module in "${modules[@]}"; do
  mysql -uroot -e "
    CREATE DATABASE IF NOT EXISTS \`paldogotgan_${module}\` DEFAULT CHARACTER SET utf8mb4;
    GRANT ALL PRIVILEGES ON \`paldogotgan\\_${module}\`.* TO '${MYSQL_USER}'@'%';"
done
