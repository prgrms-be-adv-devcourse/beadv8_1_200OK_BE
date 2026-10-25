#!/bin/bash
# 모듈별 MySQL 스키마로 기존 데이터를 옮긴다(#288). 사람이 점검하며 실행한다. deploy.sh에 넣지 않는다.
#
# 사용:
#   MYSQL_CMD="<root로 mysql을 실행하는 명령>" ./migrate.sh <명령> <기본 스키마> <앱 계정> [결과 폴더]
#
# 명령:
#   preflight  사전 점검만 한다(00_preflight.sql). 아무것도 바꾸지 않는다.
#   all        사전 점검 -> 이전 전 행 수·체크섬 기록 -> 이전(10_forward.sql) -> 사후 점검(20_verify.sql)
#              -> 이전 후 행 수·체크섬 비교. 어느 단계든 실패하면 즉시 멈춘다.
#   rollback   이전한 것을 되돌린다(90_rollback.sql, 91_rollback_drop.sql). 이전 후 새 마이그레이션이 적용되지 않았을 때만 된다.
#
# 예(로컬 compose):
#   MYSQL_CMD="docker compose -f infra/mysql/compose.yml exec -T mysql mysql -uroot -psecretpassword" \
#     infra/mysql/migration-288/migrate.sh all paldogotgan myuser
#
# 실행 전에 앱을 멈추고(docker compose stop app) 백업한다. 절차 전체는 docs/DEPLOY.md, docs/DEVELOPMENT.md를 본다.
# 결과 폴더(기본: ./out/<시각>)에 각 단계의 출력, 이전 전후의 행 수·체크섬이 남는다.
set -euo pipefail

DIR=$(cd "$(dirname "$0")" && pwd)
COMMAND=${1:?"명령이 필요하다: preflight | all | rollback"}
DB=${2:?"기본 스키마 이름이 필요하다"}
APP_USER=${3:?"앱 계정 이름이 필요하다"}
OUT=${4:-"$DIR/out/$(date +%Y%m%d-%H%M%S)"}
: "${MYSQL_CMD:?MYSQL_CMD가 필요하다(root로 mysql을 실행하는 명령)}"

# 스크립트에 값을 문자열로 끼워 넣으므로 식별자 모양이 아니면 거부한다
for value in "$DB" "$APP_USER"; do
  if ! [[ "$value" =~ ^[A-Za-z0-9_]+$ ]]; then
    echo "허용하지 않는 이름이다: $value" >&2
    exit 1
  fi
done

mkdir -p "$OUT"
echo "결과 폴더: $OUT"

# 점검 목록(tables.txt)과 이전·되돌리기 SQL이 어긋나지 않았는지 확인한다
check_scripts_cover_tables() {
  local module table
  while IFS=$'\t' read -r module table; do
    for file in 10_forward.sql 90_rollback.sql; do
      if ! grep -q "\`paldogotgan_${module}\`.\`${table}\`" "$DIR/$file"; then
        echo "$file에 $module.$table 이 없다. tables.txt와 SQL을 맞춰라." >&2
        exit 1
      fi
    done
  done < "$DIR/tables.txt"
}

# SQL 파일의 __DB__, __APP_USER__를 값으로 바꿔 root로 실행한다. 출력은 탭으로 구분된 줄이다
run_sql() {
  sed -e "s/__DB__/$DB/g" -e "s/__APP_USER__/$APP_USER/g" "$DIR/$1" | $MYSQL_CMD --batch --skip-column-names
}

# 점검 SQL을 실행하고 FAIL이 있으면 멈춘다
run_checks() {
  local file=$1 log=$2
  run_sql "$file" | tee "$log"
  if grep -q "$(printf '\tFAIL\t')" "$log"; then
    echo "점검 실패($file). $log 를 확인하라. 아무것도 이전하지 않았다." >&2
    exit 1
  fi
}

# 테이블마다 행 수와 체크섬을 적는다. $1이 before면 기본 스키마, after면 모듈 스키마의 테이블을 읽는다.
# 스키마 이름은 지우고 "테이블 행수/체크섬"만 남겨 이전 전후를 파일로 비교할 수 있게 한다
snapshot() {
  local when=$1 module table schema sql=""
  while IFS=$'\t' read -r module table; do
    if [ "$when" = before ]; then schema=$DB; else schema="paldogotgan_$module"; fi
    sql+="SELECT 'rows', '$table', COUNT(*) FROM \`$schema\`.\`$table\`;"$'\n'
    sql+="CHECKSUM TABLE \`$schema\`.\`$table\` EXTENDED;"$'\n'
  done < "$DIR/tables.txt"
  echo "$sql" | $MYSQL_CMD --batch --skip-column-names | sed -E 's/^[^.[:space:]]+\.//' | sort
}

case "$COMMAND" in
  preflight)
    check_scripts_cover_tables
    run_checks 00_preflight.sql "$OUT/preflight.log"
    echo "사전 점검 통과"
    ;;

  all)
    check_scripts_cover_tables
    run_checks 00_preflight.sql "$OUT/preflight.log"
    echo "사전 점검 통과. 이전 전 행 수·체크섬 기록"
    snapshot before > "$OUT/before.txt"
    echo "이전 실행(10_forward.sql)"
    run_sql 10_forward.sql | tee "$OUT/forward.log"
    run_checks 20_verify.sql "$OUT/verify.log"
    snapshot after > "$OUT/after.txt"
    if ! diff "$OUT/before.txt" "$OUT/after.txt" > "$OUT/snapshot.diff"; then
      echo "이전 전후 행 수·체크섬이 다르다. $OUT/snapshot.diff 를 확인하고 rollback 또는 백업 복원을 판단하라." >&2
      exit 1
    fi
    echo "이전 완료: 모든 테이블의 행 수·체크섬이 이전 전후로 같다"
    ;;

  rollback)
    # 이전 후 새 마이그레이션이 모듈 이력에 쌓였으면(행 수가 달라짐) 단순 되돌리기로는 맞지 않는다
    run_checks 20_verify.sql "$OUT/rollback-precheck.log"
    connections=$(echo "SELECT COUNT(*) FROM information_schema.processlist WHERE user = '$APP_USER';" | $MYSQL_CMD --batch --skip-column-names)
    if [ "$connections" != "0" ]; then
      echo "앱 계정 연결이 $connections 개 있다. 앱을 멈춘 뒤 다시 실행하라." >&2
      exit 1
    fi
    # 되돌리기 전(모듈 스키마) 행 수·체크섬을 기록해 두고, 되돌린 뒤(기본 스키마) 값과 비교한다.
    # 이전 후 앱이 쓴 데이터도 함께 돌아가므로 비교 기준은 이전 전이 아니라 되돌리기 직전이다
    snapshot after > "$OUT/before-rollback.txt"
    run_sql 90_rollback.sql | tee "$OUT/rollback.log"
    leftover=$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema LIKE 'paldogotgan\\_%';" | $MYSQL_CMD --batch --skip-column-names)
    if [ "$leftover" != "0" ]; then
      echo "모듈 스키마에 테이블이 $leftover 개 남아 있어 스키마를 지우지 않았다. 확인하라." >&2
      exit 1
    fi
    run_sql 91_rollback_drop.sql | tee -a "$OUT/rollback.log"
    snapshot before > "$OUT/after-rollback.txt"
    if ! diff "$OUT/before-rollback.txt" "$OUT/after-rollback.txt" > "$OUT/rollback-snapshot.diff"; then
      echo "되돌린 뒤 행 수·체크섬이 되돌리기 직전과 다르다. $OUT/rollback-snapshot.diff" >&2
      exit 1
    fi
    echo "되돌리기 완료: 모든 테이블의 행 수·체크섬이 되돌리기 직전과 같다"
    if [ -f "$OUT/before.txt" ]; then
      diff "$OUT/before.txt" "$OUT/after-rollback.txt" > "$OUT/rollback-vs-original.diff" \
        && echo "이전 전과도 같다(이전 후 앱이 쓴 데이터가 없었다)" \
        || echo "이전 전과 다른 부분이 있다(이전 후 앱이 쓴 데이터): $OUT/rollback-vs-original.diff"
    fi
    ;;

  *)
    echo "알 수 없는 명령: $COMMAND (preflight | all | rollback)" >&2
    exit 1
    ;;
esac
