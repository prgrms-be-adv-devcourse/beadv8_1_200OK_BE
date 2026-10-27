#!/bin/bash
# 모듈별 DB 계정을 <모듈>/.env 의 DB_USERNAME, DB_PASSWORD 대로 MySQL에 만든다. 계정은 자기 모듈 스키마에만 권한을 갖는다.
# 이미 떠 있는 DB에도 쓸 수 있고(initdb는 빈 데이터 디렉터리일 때만 돈다), 여러 번 실행해도 같은 결과다(비밀번호가 바뀌었으면 갱신한다).
#
#   ./infra/mysql/sync-module-users.sh           모듈 .env가 있는 모듈만 반영한다
#   ./infra/mysql/sync-module-users.sh --init    .env가 없는 모듈은 .env.example에서 만들고(비밀번호는 무작위) 반영한다
#
# 모듈 목록은 deploy/initdb/modules.txt(./gradlew generateInitDb)를 따르고, 스키마 이름은 paldogotgan_<모듈>이다.
# 기본은 이 저장소의 docker compose MySQL에 실행한다. 다른 곳이면 DB_EXEC로 명령을 바꾼다(root 비밀번호는 컨테이너의 MYSQL_ROOT_PASSWORD를 쓴다).
#   예) DB_EXEC="docker exec -i <컨테이너 이름>" ./infra/mysql/sync-module-users.sh
# 비밀번호는 명령행 인자가 아니라 표준 입력으로 넘긴다.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

INIT=false
case "${1:-}" in
  "") ;;
  --init) INIT=true ;;
  *) echo "사용법: $0 [--init]" >&2; exit 1 ;;
esac

MODULES_FILE="deploy/initdb/modules.txt"
if [[ ! -f "${MODULES_FILE}" ]]; then
  echo "모듈 목록 파일이 없다: ${MODULES_FILE} (./gradlew generateInitDb)" >&2
  exit 1
fi

# 모든 모듈이 함께 쓰는 공용 앱 계정(monolith). 모듈 계정이 이 이름이면 비밀번호를 바꿔 monolith가 깨진다
SHARED_USER="myuser"
if [[ -f .env ]]; then
  shared_from_env="$(grep -E '^MYSQL_USER=' .env | tail -1 | cut -d= -f2- | tr -d '\r' || true)"
  [[ -n "${shared_from_env}" ]] && SHARED_USER="${shared_from_env}"
fi

# KEY=VALUE 파일에서 마지막으로 나온 KEY의 값을 읽는다
read_key() {
  grep -E "^$2=" "$1" | tail -1 | cut -d= -f2- | tr -d '\r' || true
}

sql=""
seen=" "
synced=0
while IFS= read -r line || [[ -n "${line}" ]]; do
  module="${line//[[:space:]]/}"
  [[ -z "${module}" || "${module}" == \#* ]] && continue
  if ! [[ "${module}" =~ ^[a-z]+$ ]]; then
    echo "modules.txt에 허용하지 않는 모듈 이름이 있다: ${module}" >&2
    exit 1
  fi

  env_file="${module}/.env"
  if [[ ! -f "${env_file}" ]]; then
    if [[ "${INIT}" == true && -f "${module}/.env.example" ]]; then
      password="$(openssl rand -hex 16)"
      sed "s/^DB_PASSWORD=.*/DB_PASSWORD=${password}/" "${module}/.env.example" > "${env_file}"
      chmod 600 "${env_file}"
      echo "만듦: ${env_file}"
    else
      echo "건너뜀: ${env_file} 없음 (--init으로 만들 수 있다)"
      continue
    fi
  fi

  username="$(read_key "${env_file}" DB_USERNAME)"
  password="$(read_key "${env_file}" DB_PASSWORD)"

  # 값을 SQL에 그대로 끼워 넣으므로 식별자·안전한 문자만 허용한다
  if ! [[ "${username}" =~ ^[A-Za-z0-9_]{1,32}$ ]]; then
    echo "${env_file}: DB_USERNAME은 영문·숫자·_ 1~32자여야 한다" >&2
    exit 1
  fi
  if [[ "${username}" == "root" || "${username}" == "${SHARED_USER}" ]]; then
    echo "${env_file}: DB_USERNAME에 root나 공용 계정(${SHARED_USER})은 쓸 수 없다" >&2
    exit 1
  fi
  if [[ "${seen}" == *" ${username} "* ]]; then
    echo "${env_file}: DB_USERNAME(${username})이 다른 모듈과 같다. 모듈마다 달라야 스키마 권한이 나뉜다" >&2
    exit 1
  fi
  if ! [[ "${password}" =~ ^[A-Za-z0-9._~@%+=-]{12,}$ ]]; then
    echo "${env_file}: DB_PASSWORD는 영문·숫자와 . _ ~ @ % + = - 만 쓴 12자 이상이어야 한다" >&2
    exit 1
  fi
  seen="${seen}${username} "

  # IF NOT EXISTS로 만든 뒤 ALTER로 비밀번호를 맞춘다. GRANT의 DB 이름에서 _는 와일드카드라 \_로 이스케이프한다
  sql+="CREATE USER IF NOT EXISTS '${username}'@'%' IDENTIFIED BY '${password}';
ALTER USER '${username}'@'%' IDENTIFIED BY '${password}';
GRANT ALL PRIVILEGES ON \`paldogotgan\\_${module}\`.* TO '${username}'@'%';
"
  echo "반영: ${module} → ${username} (paldogotgan_${module})"
  synced=$((synced + 1))
done < "${MODULES_FILE}"

if [[ "${synced}" -eq 0 ]]; then
  echo "반영할 모듈이 없다. --init으로 .env를 만들거나 <모듈>/.env를 직접 만든다" >&2
  exit 1
fi

read -r -a exec_cmd <<< "${DB_EXEC:-docker compose exec -T mysql}"
printf '%s' "${sql}" | "${exec_cmd[@]}" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
echo "완료: ${synced}개 모듈 계정"
