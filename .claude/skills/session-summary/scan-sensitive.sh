#!/usr/bin/env bash
# 결정 기록(ADR)에 민감정보가 섞였는지 저장 전에 점검한다. 문서는 코드 저장소 이력에 남아서 머지되면 지우기 어렵다.
# 걸리면 줄 번호와 종류를 출력하고 종료 코드 1을 돌려준다. 걸린 줄은 지우거나 일반화한 뒤 다시 돌린다.
# 허용: 127.0.0.1, 0.0.0.0, @example.com 같은 예시 값. 걸린 줄의 내용은 출력하지 않는다(값이 터미널과 로그에 남지 않게 줄 번호와 종류만 보여 준다).
# 사용법: scan-sensitive.sh <파일>...
set -uo pipefail

[ "$#" -ge 1 ] || { echo "사용법: scan-sensitive.sh <파일>..." >&2; exit 2; }

# 종류|옵션|정규식 (grep -E)
rules=(
  '비밀값 대입|-i|(password|passwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key)[[:space:]]*[:=][[:space:]]*["'"'"']?[A-Za-z0-9_/+=.-]{8,}'
  'IP 주소||(^|[^0-9.])([0-9]{1,3}\.){3}[0-9]{1,3}([^0-9.]|$)'
  '이메일||[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
  'AWS 액세스 키||AKIA[0-9A-Z]{16}'
  '개인 키||-----BEGIN [A-Z ]*PRIVATE KEY-----'
  'JWT||eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}'
  '서비스 토큰||(ghp|gho|ghs|github_pat)_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|sk-[A-Za-z0-9]{20,}'
  '자격증명이 든 URL||://[^/[:space:]:@]+:[^/[:space:]@]+@'
  '내부 호스트||https?://[^/[:space:]]*\.(internal|corp|intra|local)([/:[:space:]]|$)'
)

# 예시 값은 걸러 낸다.
allowed='127\.0\.0\.1|0\.0\.0\.0|@example\.(com|org|net)'

found=0
for f in "$@"; do
  [ -f "$f" ] || { echo "파일이 없다: $f" >&2; exit 2; }
  for rule in "${rules[@]}"; do
    label="${rule%%|*}"; rest="${rule#*|}"; opt="${rest%%|*}"; re="${rest#*|}"
    # shellcheck disable=SC2086
    hits="$(tr -d '\r' < "$f" | grep -nE ${opt:+$opt} -- "$re" | grep -vE "$allowed" || true)"
    [ -n "$hits" ] || continue
    while IFS= read -r line; do
      printf '%s:%s: [%s]\n' "$f" "${line%%:*}" "$label"
      found=1
    done <<< "$hits"
  done
done

exit "$found"
