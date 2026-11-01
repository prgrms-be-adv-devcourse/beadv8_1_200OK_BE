#!/usr/bin/env bash
# docs/decisions/<모듈>/<이슈번호>-*.md 머리말을 읽어 색인 표를 표준 출력으로 낸다.
# 파일로 저장하지 않는다. 문서가 PR로 하나씩 들어오므로 색인 파일을 커밋하면 매번 충돌하고, 저장하지 않으면 낡을 일도 없다.
# 후보를 줄이는 일은 AI의 판단이 아니라 이 스크립트가 구조로 한다.
#   -m 모듈[,모듈]  그 모듈 폴더만 읽는다(기본은 전부)
#   -c 기준브랜치   그 브랜치 대비 지금 브랜치에서 바뀐 파일과 문서의 paths가 겹치는지 "겹침" 열에 표시한다
# 열 "이후 변경"은 문서 작성일 이후 paths에서 생긴 커밋 수와 지금 없는 경로 수다. 낡았을 가능성을 보는 기준이다.
# 사용법: build-index.sh [-m 모듈[,모듈]] [-c 기준브랜치]
set -euo pipefail

usage() { echo "사용법: build-index.sh [-m 모듈[,모듈]] [-c 기준브랜치]" >&2; exit 2; }

modules=""
base=""
while getopts "m:c:" opt; do
  case "$opt" in
    m) modules="$OPTARG" ;;
    c) base="$OPTARG" ;;
    *) usage ;;
  esac
done

root="$(git rev-parse --show-toplevel)"
dir="$root/docs/decisions"
[ -d "$dir" ] || { echo "docs/decisions 폴더가 없다: $dir" >&2; exit 1; }

changed=""
if [ -n "$base" ]; then
  changed="$(git -C "$root" diff --name-only "$base...HEAD" 2>/dev/null || true)"
fi

if [ -n "$modules" ]; then
  dirs=""
  IFS=',' read -r -a mods <<< "$modules"
  for m in "${mods[@]}"; do
    [ -d "$dir/$m" ] && dirs="$dirs$dir/$m"$'\n'
  done
else
  dirs="$(find "$dir" -mindepth 1 -maxdepth 1 -type d | sort)"
fi

rows="$(mktemp)"
trap 'rm -f "$rows"' EXIT
tab="$(printf '\t')"
esc() { printf '%s' "$1" | sed 's/|/\\|/g'; }

while IFS= read -r d; do
  [ -n "$d" ] || continue
  for f in "$d"/[0-9]*.md; do
    [ -e "$f" ] || continue
    # Windows 줄바꿈(CRLF)이 섞여 있어도 읽도록 \r을 지운다. 머리말(첫 --- ~ 둘째 ---)만 읽는다.
    fm="$(tr -d '\r' < "$f" | awk '/^---[[:space:]]*$/ { n++; next } n == 1 { print } n >= 2 { exit }')"
    get() { printf '%s\n' "$fm" | sed -n "s/^$1:[[:space:]]*//p" | head -1; }

    module="$(basename "$d")"
    issue="$(get issue)"; pr="$(get pr)"; status="$(get status)"; date="$(get date)"
    title="$(get title)"; summary="$(get summary)"
    raw="$(get paths)"; raw="${raw#\[}"; raw="${raw%\]}"

    paths=()
    if [ -n "$raw" ]; then
      IFS=',' read -r -a parts <<< "$raw"
      for p in "${parts[@]}"; do
        p="$(printf '%s' "$p" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
        [ -n "$p" ] && paths+=("$p")
      done
    fi

    after="-"; overlap="-"
    if [ "${#paths[@]}" -gt 0 ]; then
      missing=0
      for p in "${paths[@]}"; do [ -e "$root/$p" ] || missing=$((missing + 1)); done
      commits=0
      if [ -n "$date" ]; then
        commits="$(git -C "$root" log --since="$date 23:59:59" --oneline -- "${paths[@]}" 2>/dev/null | wc -l | tr -d ' ')"
      fi
      after="${commits}건, 없는 경로 ${missing}"
      if [ -n "$changed" ]; then
        overlap="아니오"
        while IFS= read -r cf; do
          [ -n "$cf" ] || continue
          for p in "${paths[@]}"; do
            if [ "$cf" = "$p" ] || [[ "$cf" == "$p"/* ]]; then overlap="예"; break 2; fi
          done
        done <<< "$changed"
      fi
    fi

    rel="${f#"$root"/}"
    printf '%s\t%s\t| %s | #%s | %s | %s | %s | %s | %s | %s | `%s` |\n' \
      "$module" "${issue:-0}" "$module" "$issue" "$(esc "${pr:+#$pr}")" "$(esc "$status")" "$(esc "$title")" \
      "$(esc "$summary")" "$date" "$overlap / $after" "$rel"
  done
done <<< "$dirs" | sort -t "$tab" -k1,1 -k2,2rn | cut -f3- > "$rows"

echo "# 결정 기록 색인"
echo
echo "| 모듈 | 이슈 | PR | 상태 | 제목 | 한 줄 요약 | 작성일 | 겹침 / 이후 변경 | 파일 |"
echo "|---|---|---|---|---|---|---|---|---|"
cat "$rows"
echo
echo "총 $(wc -l < "$rows" | tr -d ' ')건. 겹침은 -c로 기준 브랜치를 줬을 때만 계산한다. 이후 변경은 작성일 다음 날부터 paths에서 생긴 커밋 수다." >&2
