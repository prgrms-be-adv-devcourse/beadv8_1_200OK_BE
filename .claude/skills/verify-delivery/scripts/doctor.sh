#!/usr/bin/env bash
# 읽기 전용 점검. 이 인스턴스가 우리가 띄운 것이고 드라이브할 가치가 있는지 본다.
# 사용: VD_STATE=<state 경로> scripts/doctor.sh   (종료 코드 0 이면 정상)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/_lib.sh"
bad=0
ok()  { echo "OK   $1"; }
ng()  { echo "FAIL $1"; bad=1; }

# 1) 프로세스: 기록한 PID 가 살아 있고, 포트를 연 프로세스가 그 트리 안에 있다
kill -0 "$APP_PID" 2>/dev/null && ok "app pid $APP_PID 살아 있음" || ng "app pid $APP_PID 없음"
tree="$APP_PID"; add() { for c in $(pgrep -P "$1" 2>/dev/null); do tree="$tree $c"; add "$c"; done; }; add "$APP_PID"
owner="$(lsof -nP -iTCP:"$APP_PORT" -sTCP:LISTEN -t 2>/dev/null | head -1)"
if [ -n "$owner" ] && [[ " $tree " == *" $owner "* ]]; then ok "포트 $APP_PORT 소유자 pid $owner 는 우리 프로세스 트리"; else ng "포트 $APP_PORT 소유자($owner)가 우리 트리가 아님"; fi
[ "$APP_PORT" != 8080 ] && ok "8080 이 아님" || ng "APP_PORT 가 8080"

# 2) 컨테이너: 우리가 시작한 MySQL
[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER_ID" 2>/dev/null)" = true ] && ok "mysql 컨테이너 ${CONTAINER_ID:0:12} 실행 중" || ng "mysql 컨테이너 없음"

# 3) health
curl -fsS "http://127.0.0.1:$APP_PORT/actuator/health" | grep -q '"status":"UP"' && ok "/actuator/health UP" || ng "health 가 UP 아님"

# 4) 마이그레이션 버전 (모듈 스키마의 Flyway 이력)
last="$(mysql_exec -N -e "SELECT version FROM paldogotgan_delivery.flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1")"
expected="$(ls "$REPO/delivery/src/main/resources/db/migration/delivery/" | sed -E 's/^V([0-9]+)__.*/\1/' | sort | tail -1)"
[ "$last" = "$expected" ] && ok "마이그레이션 최신 버전 $last" || ng "마이그레이션 버전 $last (기대 $expected)"

# 5) 토큰 인증: 구매자 토큰으로 인증 통과(401 이 아님), 토큰 없으면 401
tok="$("$HERE/mint-token.py" --key "$WORK/keys/rs256-v1-private.pem" --sub 1 --roles BUYER)"
code="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $tok" "http://127.0.0.1:$APP_PORT/api/v1/shipments")"
[ "$code" = 200 ] && ok "BUYER 토큰으로 GET /api/v1/shipments = 200" || ng "BUYER 토큰 응답 $code"
code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$APP_PORT/api/v1/shipments")"
[ "$code" = 401 ] && ok "토큰 없으면 401" || ng "토큰 없는 요청 응답 $code (기대 401)"
exit $bad
