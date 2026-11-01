#!/usr/bin/env bash
# 결정 기록(ADR)에 민감정보가 섞였는지 저장 전에 점검한다. 문서는 코드 저장소 이력에 남아서 머지되면 지우기 어렵다.
# 걸리면 줄 번호와 종류를 줄 순서대로 출력하고 종료 코드 1을 돌려준다. 걸린 줄의 내용은 출력하지 않는다(값이 터미널과 로그에 남지 않게).
# 걸린 줄은 지우거나 일반화한 뒤 다시 돌린다.
# 예시 값(127.0.0.1, 0.0.0.0, @example.com, git@호스트:, <자리표시자>, ****)은 그 값만 지우고 같은 줄의 나머지를 검사한다.
# 잡는 것은 "이름: 값" 꼴의 비밀값, IP, 이메일, 키·토큰, 자격증명이 든 URL, 내부 호스트다. 문장 속에 풀어 쓴 값이나
# 개인정보, 내부 시스템의 구체적인 이름은 못 잡으므로 사람이 읽어 확인한다.
# 사용법: scan-sensitive.sh <파일>...
set -uo pipefail
export LC_ALL=C

[ "$#" -ge 1 ] || { echo "사용법: scan-sensitive.sh <파일>..." >&2; exit 2; }

# 종류|grep 옵션|정규식 (grep -E)
rules=(
  'IP 주소||(^|[^0-9.A-Za-z])([0-9]{1,3}\.){3}[0-9]{1,3}([^0-9.]|$)'
  '이메일||[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
  'AWS 액세스 키||(AKIA|ASIA)[0-9A-Z]{16}'
  '개인 키||-----BEGIN [A-Z ]*PRIVATE KEY-----'
  'JWT||eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}'
  '서비스 토큰||(^|[^A-Za-z0-9])(ghp|gho|ghs|github_pat)_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|(^|[^A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}'
  '자격증명이 든 URL||://[^/[:space:]:@]+:[^/[:space:]@]+@'
  '내부 호스트||https?://[^/[:space:]]*\.(internal|corp|intra|local)([/:[:space:]]|$)|ip-([0-9]{1,3}-){3}[0-9]{1,3}\.|[A-Za-z0-9.-]+\.(rds|elb|elasticache|execute-api)\.[A-Za-z0-9.-]*amazonaws\.com'
)

# "이름: 값" 꼴의 비밀값. 값은 이름 뒤 구분자(: 또는 =) 다음의 공백 없는 ASCII 덩어리이고, 문장 끝 구두점을 뗀 뒤
# 8자 이상이면서 숫자나 기호가 하나라도 있을 때만 잡는다. 안쪽 match()가 RSTART와 RLENGTH를 덮어쓰므로 바깥 값은 먼저 저장한다. "password: required."나 "token: 만료" 같은 설명은 지나간다.
read -r -d '' SECRET_AWK <<'AWKEOF'
{
  line = $0; low = tolower(line); rest = low; off = 0
  while (match(rest, /(password|passwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key)[ \t]*[:=][ \t]*["']?/)) {
    ms = RSTART; ml = RLENGTH
    start = off + ms + ml
    val = substr(line, start)
    if (match(val, /^[!-~]+/)) {
      v = substr(val, 1, RLENGTH)
      sub(/[.,;:)\]]+$/, "", v)
      if (length(v) >= 8 && v ~ /[^A-Za-z]/) { print NR; next }
    }
    off += ms + ml - 1
    rest = substr(low, off + 1)
  }
}
AWKEOF

mask() {
  sed -E \
    -e 's/(^|[^0-9.A-Za-z])(127\.0\.0\.1|0\.0\.0\.0)([^0-9.]|$)/\1 \3/g' \
    -e 's/@example\.(com|org|net)([^A-Za-z0-9.-]|$)/ \2/g' \
    -e 's/git@[A-Za-z0-9.-]+:/ /g' \
    -e 's/<[^<>]*>/ /g' \
    -e 's/\*{4,}/ /g'
}

found=0
for f in "$@"; do
  [ -f "$f" ] || { echo "파일이 없다: $f" >&2; exit 2; }
  hits=()
  lines="$(tr -d '\r' < "$f" | mask | mask | awk "$SECRET_AWK")"
  if [ -n "$lines" ]; then
    while IFS= read -r no; do hits+=("$no|비밀값 대입"); done <<< "$lines"
  fi
  for rule in "${rules[@]}"; do
    label="${rule%%|*}"; rest="${rule#*|}"; opt="${rest%%|*}"; re="${rest#*|}"
    # shellcheck disable=SC2086
    lines="$(tr -d '\r' < "$f" | mask | mask | grep -anE ${opt:+$opt} -- "$re" | cut -d: -f1)"
    [ -n "$lines" ] || continue
    while IFS= read -r no; do hits+=("$no|$label"); done <<< "$lines"
  done
  if [ "${#hits[@]}" -gt 0 ]; then
    found=1
    printf '%s\n' "${hits[@]}" | sort -t'|' -k1,1n -k2,2 -u | while IFS='|' read -r no label; do
      printf '%s:%s: [%s]\n' "$f" "$no" "$label"
    done
  fi
done

exit "$found"
