#!/usr/bin/env bash
# docs/decisions/<모듈>/<이슈번호>-*.md 머리말을 읽어 색인 표를 표준 출력으로 낸다.
# 파일로 저장하지 않는다. 문서가 PR로 하나씩 들어오므로 색인 파일을 커밋하면 매번 충돌하고, 저장하지 않으면 낡을 일도 없다.
# 후보를 줄이는 일은 AI의 판단이 아니라 이 스크립트가 구조로 한다.
#   -m 모듈[,모듈]  그 모듈의 문서만 낸다. -c를 같이 주면 다른 모듈이라도 paths가 겹치는 문서는 함께 낸다
#   -c 기준브랜치   그 브랜치 대비 지금 브랜치에서 바뀐 파일과 문서의 paths가 겹치는지 "겹침"에 표시한다
# "이후 변경"은 문서를 마지막으로 바꾼 커밋 이후에 paths를 바꾼 커밋 수(문서를 건드린 커밋은 제외)와 지금 없는 경로 수다.
# 문서와 코드가 한 PR에서 squash 머지돼도 그 커밋은 세지 않는다. 낡았을 가능성을 보는 기준이다.
# 머리말은 한 줄 값만 읽는다. paths는 [a, b] 한 줄 목록으로 쓴다.
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
  git -C "$root" rev-parse --verify -q "$base^{commit}" > /dev/null \
    || { echo "기준 브랜치를 찾을 수 없다: $base" >&2; exit 2; }
  changed="$(git -C "$root" diff --name-only "$base...HEAD")"
fi

rows="$(mktemp)"
trap 'rm -f "$rows"' EXIT
tab="$(printf '\t')"
esc() { printf '%s' "$1" | sed 's/|/\\|/g'; }
unquote() { sed -E "s/^\"(.*)\"\$/\\1/; s/^'(.*)'\$/\\1/"; }

while IFS= read -r f; do
  [ -n "$f" ] || continue
  # Windows 줄바꿈(CRLF)이 섞여 있어도 읽도록 \r을 지운다. 첫 줄이 ---일 때만 머리말(첫 --- ~ 둘째 ---)을 읽는다.
  fm="$(tr -d '\r' < "$f" | awk 'NR == 1 { if ($0 !~ /^---[ \t]*$/) exit; next } /^---[ \t]*$/ { exit } { print }')"
  [ -n "$fm" ] || continue
  get() { printf '%s\n' "$fm" | sed -n "s/^$1:[[:space:]]*//p" | head -1 | unquote; }

  module="$(basename "$(dirname "$f")")"
  issue="$(get issue)"; pr="$(get pr)"; status="$(get status)"; date="$(get date)"
  title="$(get title)"; summary="$(get summary)"
  raw="$(get paths)"; raw="${raw#\[}"; raw="${raw%\]}"
  rel="${f#"$root"/}"

  paths=()
  if [ -n "$raw" ]; then
    IFS=',' read -r -a parts <<< "$raw"
    for p in "${parts[@]}"; do
      p="$(printf '%s' "$p" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | unquote)"
      while [ "${p%/}" != "$p" ]; do p="${p%/}"; done
      [ -n "$p" ] && paths+=("$p")
    done
  fi

  after="-"; overlap="-"
  if [ "${#paths[@]}" -gt 0 ]; then
    missing=0
    for p in "${paths[@]}"; do [ -e "$root/$p" ] || missing=$((missing + 1)); done

    dts="$(git -C "$root" log -1 --format=%cI -- "$rel" 2>/dev/null || true)"
    if [ -n "$dts" ]; then
      doc_commits="$(git -C "$root" log --format=%H -- "$rel")"
      path_commits="$(git -C "$root" log --format=%H --since="$dts" -- "${paths[@]}" 2>/dev/null || true)"
      commits="$(comm -23 <(printf '%s\n' "$path_commits" | sort) <(printf '%s\n' "$doc_commits" | sort) | grep -c . || true)"
    elif [ -n "$date" ]; then
      # 아직 커밋하지 않은 문서는 작성일 다음 날부터 센다.
      commits="$(git -C "$root" log --since="$date 23:59:59" --oneline -- "${paths[@]}" 2>/dev/null | wc -l | tr -d ' ')"
    else
      commits=0
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

  if [ -n "$modules" ]; then
    keep=0
    case ",$modules," in *",$module,"*) keep=1 ;; esac
    [ "$overlap" = "예" ] && keep=1
    [ "$keep" = 1 ] || continue
  fi

  printf '%s\t%s\t| %s | #%s | %s | %s | %s | %s | %s | %s | `%s` |\n' \
    "$module" "${issue:-0}" "$module" "$issue" "$(esc "${pr:+#$pr}")" "$(esc "$status")" "$(esc "$title")" \
    "$(esc "$summary")" "$date" "$overlap / $after" "$(esc "$rel")"
done < <(find "$dir" -mindepth 2 -maxdepth 2 -type f -name '[0-9]*.md' | sort) | sort -t "$tab" -k1,1 -k2,2rn | cut -f3- > "$rows"

echo "# 결정 기록 색인"
echo
echo "| 모듈 | 이슈 | PR | 상태 | 제목 | 한 줄 요약 | 작성일 | 겹침 / 이후 변경 | 파일 |"
echo "|---|---|---|---|---|---|---|---|---|"
cat "$rows"
echo
echo "총 $(wc -l < "$rows" | tr -d ' ')건. 겹침은 -c로 기준 브랜치를 줬을 때만 계산한다. 이후 변경은 문서를 마지막으로 바꾼 커밋 이후 paths를 바꾼 커밋 수다." >&2
