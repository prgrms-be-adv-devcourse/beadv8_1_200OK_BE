#!/usr/bin/env bash
# 검증 DB 에 읽기 쿼리를 보내고 결과를 증거 폴더에 남긴다 (부수효과 확인용).
# 사용: VD_STATE=<state> scripts/sql.sh <증거이름> "<SQL>"
# 예:   scripts/sql.sh buyer-shipment/db-row "SELECT id,status,carrier FROM shipment WHERE id=1001"
# 기본 스키마는 paldogotgan_delivery 이다.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/_lib.sh"
OUT="$EVID/$1"; mkdir -p "$(dirname "$OUT")"
{ echo "SQL> $2"; mysql_exec --table paldogotgan_delivery -e "$2"; } | tee "$OUT.sql.txt"
