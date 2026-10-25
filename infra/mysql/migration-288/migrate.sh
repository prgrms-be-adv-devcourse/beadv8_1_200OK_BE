#!/bin/bash
# 모듈별 MySQL 스키마로 기존 데이터를 옮긴다(#288). 사람이 점검하며 실행한다. deploy.sh에 넣지 않는다.
#
# 사용:
#   [MYSQL_EXEC="<mysql 컨테이너에 명령을 실행하는 접두어>"] ./migrate.sh <명령> <기본 스키마> <앱 계정> [결과 폴더]
#
# 명령:
#   preflight  사전 점검만 한다(00_preflight.sql). 아무것도 바꾸지 않는다.
#   all        사전 점검 -> 이전 전 행 수·체크섬 기록 -> 이전(10_forward.sql) -> 사후 점검(20_verify.sql)
#              -> 이전 후 비교. 어느 단계든 실패하면 즉시 멈추고, 이전 중 실패하면 상태와 다음 할 일을 출력한다.
#   rollback   이전한 것을 되돌린다(90_rollback.sql, 91_rollback_drop.sql). 이전 후 새 마이그레이션이 적용되지 않았을 때만 된다.
#              이전 도중 실패한 상태에서도 다시 실행할 수 있다.
#   cleanup    테이블을 옮기기 전에 이전이 실패했을 때 만들어 둔 빈 모듈 스키마와 이력 복사본을 지운다.
#
# root 계정으로 mysql을 실행한다. 비밀번호는 명령행에 쓰지 않는다.
#   - MYSQL_EXEC가 있으면 그 접두어로 mysql 컨테이너 안에서 실행하고, 컨테이너의 MYSQL_ROOT_PASSWORD 환경변수를 쓴다.
#       예(운영): MYSQL_EXEC="docker compose -f compose.prod.yml exec -T mysql"
#       예(로컬): MYSQL_EXEC="docker compose -f infra/mysql/compose.yml exec -T mysql"
#   - 없으면 이 컴퓨터의 mysql 클라이언트로 root에 접속한다(비밀번호는 MYSQL_PWD 환경변수).
#
# 환경변수 SNAPSHOT_MODE=rows 이면 테이블마다 CHECKSUM TABLE을 하지 않고 행 수만 기록·비교한다(기본 full).
# CHECKSUM TABLE은 테이블 전체를 읽으므로 데이터가 크면 오래 걸린다(이전 자체는 메타데이터 작업이라 크기와 무관하다).
#
# 실행 전에 앱을 멈추고(docker compose stop app) 백업한다. 절차 전체는 docs/DEPLOY.md, docs/DEVELOPMENT.md를 본다.
# 결과 폴더(기본: ./out/<시각>)에 각 단계의 출력, 이전 전후의 행 수·체크섬이 남는다.
set -euo pipefail

DIR=$(cd "$(dirname "$0")" && pwd)
COMMAND=${1:?"명령이 필요하다: preflight | all | rollback | cleanup"}
DB=${2:?"기본 스키마 이름이 필요하다"}
APP_USER=${3:?"앱 계정 이름이 필요하다"}
OUT=${4:-"$DIR/out/$(date +%Y%m%d-%H%M%S)"}
SNAPSHOT_MODE=${SNAPSHOT_MODE:-full}
SCHEMA_PREFIX="paldogotgan_"
MODULES="member wallet order product delivery payout media refund"
STUB_COMMENT='migration-288 placeholder: 이전 전 이미지가 이전된 DB에서 기동하지 않게 하는 자리표시 테이블'

# 스크립트에 값을 문자열로 끼워 넣으므로 식별자 모양이 아니면 거부한다
for value in "$DB" "$APP_USER"; do
  if ! [[ "$value" =~ ^[A-Za-z0-9_]+$ ]]; then
    echo "허용하지 않는 이름이다: $value" >&2
    exit 1
  fi
done
# 모듈 스키마 이름을 기본 스키마로 잘못 넣으면 엉뚱한 곳을 옮기게 된다
if [[ "$DB" == ${SCHEMA_PREFIX}* ]]; then
  echo "기본 스키마 이름이 모듈 스키마 접두사(${SCHEMA_PREFIX})로 시작한다: $DB. 기본 스키마(MYSQL_DATABASE 값)를 넘겨라." >&2
  exit 1
fi

mkdir -p "$OUT"
echo "결과 폴더: $OUT"

# root로 mysql을 실행한다. 비밀번호는 컨테이너의 환경변수(또는 MYSQL_PWD)에서 읽고 명령행에 나타나지 않는다
mysql_cli() {
  if [ -n "${MYSQL_EXEC:-}" ]; then
    # shellcheck disable=SC2086 (MYSQL_EXEC는 공백으로 나뉜 명령 접두어다. 비밀값이 없다)
    $MYSQL_EXEC sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$@"' sh "$@"
  else
    mysql -uroot "$@"
  fi
}

# 한 줄 질의의 결과 값 하나를 돌려준다
query() {
  echo "$1" | mysql_cli --batch --skip-column-names
}

# SQL 파일의 __DB__, __APP_USER__를 값으로 바꿔 실행한다. 출력은 탭으로 구분된 줄이다
run_sql() {
  local file=$1
  shift
  sed -e "s/__DB__/$DB/g" -e "s/__APP_USER__/$APP_USER/g" "$DIR/$file" | mysql_cli --batch --skip-column-names "$@"
}

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

# 사전 점검 SQL의 "알려진 마이그레이션 목록"이 이 저장소의 마이그레이션 파일과 같은지 본다(저장소 안에서 실행할 때만).
# 이 PR 이후에 병합된 마이그레이션이 있으면 달라지는데, 그 마이그레이션이 이미 적용된 DB는 사전 점검 (b)에서 멈추므로 목록 갱신이 필요한지 판단한다
warn_if_known_scripts_differ() {
  local root="$DIR/../../.."
  [ -f "$root/settings.gradle.kts" ] || return 0
  local repo known
  repo=$(find "$root" -path '*/build' -prune -o -path '*/src/main/resources/db/migration/*' -name 'V*.sql' -print | sed 's#.*/##' | sort -u)
  known=$(grep -o "'V[0-9]*__[^']*\.sql'" "$DIR/00_preflight.sql" | tr -d "'" | sort -u)
  if [ "$repo" != "$known" ]; then
    echo "경고: 00_preflight.sql의 알려진 마이그레이션 목록이 저장소의 마이그레이션 파일과 다르다. 차이:" >&2
    diff <(echo "$known") <(echo "$repo") >&2 || true
  fi
}

# 점검 SQL을 실행하고 FAIL이 있으면 멈춘다
run_checks() {
  local file=$1 log=$2 note=$3
  run_sql "$file" | tee "$log"
  if grep -q "$(printf '\tFAIL\t')" "$log"; then
    echo "점검 실패($file). $log 를 확인하라. $note" >&2
    exit 1
  fi
}

# 모듈 스키마 안에 flyway_schema_history 말고 테이블이 몇 개 있는지
moved_table_count() {
  query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema LIKE 'paldogotgan\\_%' AND table_name NOT IN ('flyway_schema_history', 'media_placeholder');"
}

# 이전 도중 실패했을 때 지금 상태와 다음 할 일을 알려 준다
describe_state_after_failure() {
  local moved
  moved=$(moved_table_count || echo "?")
  echo "---- 이전 도중 실패. 현재 상태 ----" >&2
  echo "모듈 스키마로 옮겨진 테이블 수: $moved (정상 완료 시 52)" >&2
  if [ "$moved" = "0" ]; then
    echo "테이블은 옮겨지지 않았다(RENAME 전 실패 또는 RENAME 실패). 원인을 고친 뒤 '$0 cleanup $DB $APP_USER $OUT'로 만들어 둔 빈 스키마와 이력 복사본을 지우고 처음부터 다시 실행한다." >&2
  else
    echo "테이블이 옮겨졌다. '$0 rollback $DB $APP_USER $OUT'로 되돌린 뒤 원인을 고쳐 다시 실행하거나, 점검(20_verify.sql)을 직접 확인한다. 어느 쪽이든 앱을 올리지 않는다." >&2
  fi
}

# 테이블마다 행 수와 체크섬을 적는다. $1이 before면 기본 스키마, after면 모듈 스키마의 테이블을 읽는다.
# 스키마 이름은 지우고 "테이블 행수/체크섬"만 남겨 이전 전후를 파일로 비교할 수 있게 한다
snapshot() {
  local when=$1 module table schema sql=""
  while IFS=$'\t' read -r module table; do
    if [ "$when" = before ]; then schema=$DB; else schema="${SCHEMA_PREFIX}$module"; fi
    sql+="SELECT 'rows', '$table', COUNT(*) FROM \`$schema\`.\`$table\`;"$'\n'
    if [ "$SNAPSHOT_MODE" != rows ]; then
      sql+="CHECKSUM TABLE \`$schema\`.\`$table\` EXTENDED;"$'\n'
    fi
  done < "$DIR/tables.txt"
  echo "$sql" | mysql_cli --batch --skip-column-names | sed -E 's/^[^.[:space:]]+\.//' | sort
}

# 앱이 멈춰 있고 열린 트랜잭션이 없는지 본다(있으면 RENAME이 메타데이터 락을 기다린다)
require_idle() {
  local connections transactions
  connections=$(query "SELECT COUNT(*) FROM information_schema.processlist WHERE user = '$APP_USER';")
  transactions=$(query "SELECT COUNT(*) FROM information_schema.innodb_trx;")
  if [ "$connections" != "0" ] || [ "$transactions" != "0" ]; then
    echo "앱 계정 연결 $connections 개, 열린 트랜잭션 $transactions 개가 있다. 앱을 멈추고 다시 실행하라." >&2
    exit 1
  fi
}

case "$COMMAND" in
  preflight)
    check_scripts_cover_tables
    warn_if_known_scripts_differ
    run_checks 00_preflight.sql "$OUT/preflight.log" "아무것도 이전하지 않았다. 이력이 정확히 55건(성공)인 DB에서만 이전한다. 로컬 DB가 일부만 적용됐으면 초기화(방법 B, docs/DEVELOPMENT.md)하라."
    echo "사전 점검 통과"
    ;;

  all)
    check_scripts_cover_tables
    warn_if_known_scripts_differ
    run_checks 00_preflight.sql "$OUT/preflight.log" "아무것도 이전하지 않았다. 이력이 정확히 55건(성공)인 DB에서만 이전한다. 로컬 DB가 일부만 적용됐으면 초기화(방법 B, docs/DEVELOPMENT.md)하라."
    echo "사전 점검 통과. 이전 전 행 수·체크섬 기록(SNAPSHOT_MODE=$SNAPSHOT_MODE)"
    snapshot before > "$OUT/before.txt"
    echo "이전 실행(10_forward.sql)"
    if ! run_sql 10_forward.sql | tee "$OUT/forward.log"; then
      describe_state_after_failure
      exit 1
    fi
    run_checks 20_verify.sql "$OUT/verify.log" "이전은 끝났다. 앱을 올리지 말고 원인을 확인하거나 rollback 한다."
    snapshot after > "$OUT/after.txt"
    if ! diff "$OUT/before.txt" "$OUT/after.txt" > "$OUT/snapshot.diff"; then
      echo "이전 전후 행 수·체크섬이 다르다. $OUT/snapshot.diff 를 확인하고 rollback 또는 백업 복원을 판단하라." >&2
      exit 1
    fi
    echo "이전 완료: 모든 테이블의 행 수·체크섬이 이전 전후로 같다"
    ;;

  rollback)
    require_idle
    moved=$(moved_table_count)
    if [ "$moved" = "0" ]; then
      echo "모듈 스키마에 옮겨진 테이블이 없다. 이전하지 않았거나 이미 되돌렸다. 빈 스키마가 남았으면 cleanup을 실행하라." >&2
      exit 1
    fi
    # 기본 스키마 이력에 실패한 마이그레이션 행(success = 0)이 없어야 한다. 이전 전 이미지를 이전된 DB에서 띄우면 남는 흔적이고,
    # 순번이 겹쳐 복원할 이력 행이 건너뛰어질 수 있으므로 지우고 다시 실행하게 한다. 행 수는 보지 않는다:
    # 이전 직후 이력은 1행이지만, 이전 중 기본 이력 정리만 실패해 성공 행 55개가 남은 상태는 복원이 중복을 건너뛰므로 허용한다
    base_failed=$(query "SELECT COUNT(*) FROM \`$DB\`.flyway_schema_history WHERE success = 0;")
    if [ "$base_failed" != "0" ]; then
      echo "기본 스키마 이력에 실패한 마이그레이션 행이 $base_failed 개 있다(이전 전 이미지를 이전된 DB에서 띄운 흔적). 확인해 지우고(DELETE FROM $DB.flyway_schema_history WHERE success = 0) 다시 실행하라." >&2
      exit 1
    fi
    # 이전 후 새 마이그레이션이 모듈 이력에 쌓였으면(행 수가 달라짐) 단순 되돌리기로는 맞지 않는다
    history_rows=$(query "SELECT SUM(c) FROM (
      SELECT COUNT(*) c FROM paldogotgan_member.flyway_schema_history UNION ALL
      SELECT COUNT(*) FROM paldogotgan_wallet.flyway_schema_history UNION ALL
      SELECT COUNT(*) FROM paldogotgan_order.flyway_schema_history UNION ALL
      SELECT COUNT(*) FROM paldogotgan_product.flyway_schema_history UNION ALL
      SELECT COUNT(*) FROM paldogotgan_delivery.flyway_schema_history UNION ALL
      SELECT COUNT(*) FROM paldogotgan_payout.flyway_schema_history UNION ALL
      SELECT COUNT(*) FROM paldogotgan_media.flyway_schema_history) h;")
    if [ "$history_rows" != "54" ]; then
      echo "모듈 이력 행이 합계 $history_rows 개다(이전 직후 54). 이전 후 새 마이그레이션이 적용됐다면 되돌리기로는 맞지 않는다. 백업으로 복원하라." >&2
      exit 1
    fi
    # 되돌리기 전(모듈 스키마) 행 수·체크섬을 기록해 두고, 되돌린 뒤(기본 스키마) 값과 비교한다.
    # 이전 후 앱이 쓴 데이터도 함께 돌아가므로 비교 기준은 이전 전이 아니라 되돌리기 직전이다
    snapshot after > "$OUT/before-rollback.txt"
    # 자리표시 테이블(media)이 기본 스키마에 있으면 90_rollback.sql의 RENAME 맨 앞에서 모듈 스키마로 함께 옮겨진다(실패하면 함께 남는다).
    # 자리표시가 아닌 media나 아예 없는 경우에는 그 줄을 뺀다. 자리표시가 아닌 media는 건드리지 않는다
    stub=$(query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$DB' AND table_name = 'media' AND table_comment = '$STUB_COMMENT';")
    rollback_sql="$OUT/90_rollback.run.sql"
    if [ "$stub" = "1" ]; then
      cp "$DIR/90_rollback.sql" "$rollback_sql"
    else
      sed '/-- STUB$/d' "$DIR/90_rollback.sql" > "$rollback_sql"
    fi
    if ! sed -e "s/__DB__/$DB/g" -e "s/__APP_USER__/$APP_USER/g" "$rollback_sql" | mysql_cli --batch --skip-column-names | tee "$OUT/rollback.log"; then
      echo "되돌리기 실패. 테이블 되돌리기(RENAME)가 먼저 실행되므로 실패하면 아무것도 바뀌지 않았다(자리표시 테이블도 그대로 남아 있다). 원인을 확인하고 같은 명령을 다시 실행해도 된다(이력 복원은 중복을 건너뛴다). 성공하기 전에 이전 전 이미지를 띄우지 않는다." >&2
      exit 1
    fi
    leftover=$(moved_table_count)
    if [ "$leftover" != "0" ]; then
      echo "모듈 스키마에 테이블이 $leftover 개 남아 있어 스키마를 지우지 않았다. 확인하라." >&2
      exit 1
    fi
    # 권한이 없는 스키마가 있어도 계속하도록 --force. 지워졌는지는 아래에서 확인한다
    run_sql 91_rollback_drop.sql --force | tee -a "$OUT/rollback.log" || true
    remaining=$(query "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name LIKE 'paldogotgan\\_%';")
    # 이스케이프한 이름(paldogotgan\_x)과 이스케이프 없이 준 이전 방식(paldogotgan_x)을 모두 센다
    grants=$(query "SELECT COUNT(*) FROM mysql.db WHERE User = '$APP_USER' AND REPLACE(Db, '\\\\', '') LIKE 'paldogotgan\\\\_%';")
    if [ "$remaining" != "0" ] || [ "$grants" != "0" ]; then
      echo "모듈 스키마 $remaining 개 또는 앱 계정 권한 $grants 개가 남았다. $OUT/rollback.log 를 확인하라." >&2
      exit 1
    fi
    restored=$(query "SELECT COUNT(*) FROM \`$DB\`.flyway_schema_history WHERE success = 1;")
    if [ "$restored" != "55" ]; then
      echo "복원된 기본 스키마 이력이 $restored 행이다(기대 55). 순번이 겹친 행이 건너뛰어졌을 수 있다. 이전 전 이미지를 띄우지 말고 백업과 대조하라." >&2
      exit 1
    fi
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

  cleanup)
    require_idle
    moved=$(moved_table_count)
    if [ "$moved" != "0" ]; then
      echo "모듈 스키마에 옮겨진 테이블이 $moved 개 있어 cleanup을 하지 않는다. rollback을 쓴다." >&2
      exit 1
    fi
    # 이력 복사본만 남은 빈 스키마를 지운다(DROP DATABASE가 이력 복사본도 함께 지운다)
    run_sql 91_rollback_drop.sql --force | tee "$OUT/cleanup.log" || true
    remaining=$(query "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name LIKE 'paldogotgan\\_%';")
    if [ "$remaining" != "0" ]; then
      echo "모듈 스키마 $remaining 개가 남았다. $OUT/cleanup.log 를 확인하라." >&2
      exit 1
    fi
    echo "정리 완료. 원인을 고친 뒤 all을 다시 실행할 수 있다"
    ;;

  *)
    echo "알 수 없는 명령: $COMMAND (preflight | all | rollback | cleanup)" >&2
    exit 1
    ;;
esac
