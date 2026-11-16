#!/usr/bin/env bash
# 인증 토큰을 붙여 curl 을 보내고 요청, 응답 본문, HTTP 상태를 증거 폴더에 남긴다. 화면에도 보여 준다.
# 사용: VD_STATE=<state> scripts/call.sh <증거이름> <역할:회원ID|none> <METHOD> <경로> [JSON 본문]
# 예:   scripts/call.sh fee-quotes/general none POST /api/v1/shipments/fee-quotes '{"postalCode":"06236","sellers":[{"sellerId":10,"itemAmount":45000}]}'
#       scripts/call.sh buyer-detail BUYER:100 GET /api/v1/shipments/1001
#       역할은 쉼표로 여러 개: SELLER,BUYER:10
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/_lib.sh"
NAME="$1"; WHO="$2"; METHOD="$3"; PATHQ="$4"; BODY="${5:-}"
OUT="$EVID/$NAME"; mkdir -p "$(dirname "$OUT")"

args=(-sS -X "$METHOD" -o "$OUT.resp.json" -w '%{http_code}' "http://127.0.0.1:$APP_PORT$PATHQ")
{ echo "$METHOD $PATHQ"; } > "$OUT.req.txt"
if [ "$WHO" != none ]; then
  roles="${WHO%%:*}"; sub="${WHO##*:}"
  # shellcheck disable=SC2046
  tok="$("$HERE/mint-token.py" --key "$WORK/keys/rs256-v1-private.pem" --sub "$sub" --roles $(echo "$roles" | tr ',' ' '))"
  args+=(-H "Authorization: Bearer $tok")
  echo "Authorization: Bearer <${roles} sub=${sub}>" >> "$OUT.req.txt"
fi
if [ -n "$BODY" ]; then
  args+=(-H 'Content-Type: application/json' --data "$BODY")
  echo "$BODY" >> "$OUT.req.txt"
fi
status="$(curl "${args[@]}")"
echo "$status" > "$OUT.status.txt"
echo "--- $NAME"; cat "$OUT.req.txt"; echo "=> HTTP $status"; python3 -m json.tool --no-ensure-ascii < "$OUT.resp.json" 2>/dev/null || cat "$OUT.resp.json"
