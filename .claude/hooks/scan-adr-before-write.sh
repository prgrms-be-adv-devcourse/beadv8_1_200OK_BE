#!/usr/bin/env bash
# docs/decisions 아래 문서를 쓰기 직전에 민감정보 검사를 돌려, 걸리면 쓰기를 막는다(PreToolUse 훅).
# 값이 파일에 한 번도 쓰이지 않게 하려고 쓰기 전에 검사한다. 문서는 코드 저장소 이력에 남아서 머지되면 지우기 어렵다.
# 걸리면 종료 코드 2로 쓰기를 막고, stderr의 줄 번호와 종류를 AI에게 돌려준다. 걸린 줄의 내용은 출력하지 않는다.
# 줄 번호는 이번에 쓰려는 내용(Write는 문서 전체, Edit는 바꿀 부분) 기준이다.
# 검사 규칙은 session-summary 스킬의 scan-sensitive.sh를 그대로 쓴다. 이 훅은 호출만 한다.
#
# 한계: Write·Edit·MultiEdit만 본다. Bash로 파일을 만들거나 IDE에서 직접 쓰는 것은 막지 못하므로 PR 리뷰가 마지막 방어선이다.
# jq가 없으면 검사하지 못하고 통과시킨다(팀원 환경 때문에 쓰기를 통째로 막지 않으려는 선택이다). 그때는 경고를 stderr에 남긴다.
# set -e를 쓰지 않는 이유: scan-sensitive.sh가 걸리면 1을 돌려주고 그 값을 받아 처리해야 한다.
set -uo pipefail

input="$(cat)"

if ! command -v jq > /dev/null 2>&1; then
  echo "경고: jq가 없어 ADR 민감정보 검사를 건너뛴다. 저장 전에 scan-sensitive.sh를 직접 돌린다." >&2
  exit 0
fi

file_path="$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty')"
# Windows 경로(C:\...\docs\decisions\...)도 같은 패턴으로 매칭하려고 구분자를 /로 통일한다.
file_path="${file_path//\\//}"
case "$file_path" in
  */docs/decisions/*.md | docs/decisions/*.md) ;;
  *) exit 0 ;;
esac

# Write는 content, Edit는 new_string, MultiEdit는 edits[].new_string에 쓸 내용이 있다.
content="$(printf '%s' "$input" | jq -r '[.tool_input.content, .tool_input.new_string, (.tool_input.edits[]?.new_string)] | map(select(. != null)) | join("\n")')"
[ -n "$content" ] || exit 0

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
printf '%s\n' "$content" > "$tmp"

scan="$(dirname "${BASH_SOURCE[0]}")/../skills/session-summary/scan-sensitive.sh"
result="$(bash "$scan" "$tmp" 2>&1)"
status=$?

if [ "$status" -eq 0 ]; then
  exit 0
fi

{
  echo "차단: ADR에 민감정보로 보이는 내용이 있어 쓰지 않았다 ($file_path)."
  echo "아래 줄을 지우거나 일반화한 뒤(파일 경로와 이름으로 가리키고 값은 옮기지 않는다) 다시 쓴다. 줄 번호는 이번에 쓰려던 내용 기준이다."
  # 임시 파일 경로는 의미가 없으므로 줄 번호와 종류만 남긴다.
  printf '%s\n' "$result" | sed "s#$tmp#쓰려던 내용#g"
} >&2
exit 2
