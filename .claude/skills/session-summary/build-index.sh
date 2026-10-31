#!/usr/bin/env bash
# sessions/<레포>/<PR번호>-*.md 머리말에서 PR 번호, 제목, 한 줄 요약을 읽어 색인(index.md)을 만든다.
# 색인은 문서 레포에 커밋하지 않는다. 여러 사람이 main에 직접 올리면 생성 파일이 항상 충돌하므로,
# 읽는 쪽(session-context)이 필요할 때마다 로컬에서 새로 만들고 문서 레포의 .gitignore에 sessions/index.md를 둔다.
# 사용법: build-index.sh <문서 폴더> [출력 파일]   (출력 기본값: <문서 폴더>/sessions/index.md)
set -euo pipefail

dir="${1:?문서 폴더를 인수로 준다}"
sessions="$dir/sessions"
out="${2:-$sessions/index.md}"
[ -d "$sessions" ] || { echo "sessions 폴더가 없다: $sessions" >&2; exit 1; }

rows="$(mktemp)"
trap 'rm -f "$rows"' EXIT
tab="$(printf '\t')"

for f in "$sessions"/*/[0-9]*.md; do
  [ -e "$f" ] || continue
  repo="$(basename "$(dirname "$f")")"
  # Windows 줄바꿈(CRLF)이 섞여 있어도 읽도록 \r을 지운다. 머리말(첫 --- ~ 둘째 ---)만 읽는다.
  tr -d '\r' < "$f" | awk -v repo="$repo" -v name="$(basename "$f")" '
    /^---[[:space:]]*$/ { fm++; next }
    fm == 1 {
      key = $0; sub(/:.*/, "", key)
      val = $0; sub(/^[^:]*:[[:space:]]*/, "", val)
      gsub(/\|/, "\\|", val)
      v[key] = val
    }
    fm >= 2 { exit }
    END {
      printf "%s\t%s\t| %s | #%s | %s | %s | %s | `%s/%s` |\n", repo, v["pr"], repo, v["pr"], v["title"], v["summary"], v["session_date"], repo, name
    }'
done | sort -t "$tab" -k1,1 -k2,2rn | cut -f3- > "$rows"

{
  echo "# 세션 요약 색인"
  echo
  echo "이 파일은 build-index.sh가 로컬에서 만든다. 커밋하지 않고 직접 고치지 않는다."
  echo
  echo "| 레포 | PR | 제목 | 한 줄 요약 | 날짜 | 파일 |"
  echo "|---|---|---|---|---|---|"
  cat "$rows"
} > "$out"

echo "색인을 만들었다: $out ($(wc -l < "$rows" | tr -d ' ')건)"
