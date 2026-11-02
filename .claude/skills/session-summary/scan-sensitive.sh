#!/usr/bin/env bash
# 결정 기록(ADR)에 민감정보가 섞였는지 저장 전에 점검한다. 문서는 코드 저장소 이력에 남아서 머지되면 지우기 어렵다.
# 걸리면 줄 번호와 종류를 줄 순서대로 출력하고 종료 코드 1을 돌려준다. 걸린 줄의 내용은 출력하지 않는다(값이 터미널과 로그에 남지 않게).
# 걸린 줄은 지우거나 일반화한 뒤 다시 돌린다.
# 통과했다고 안전한 것은 아니다. 이 스크립트는 자주 나오는 꼴만 잡으므로 통과해도 사람이 문서를 읽어 확인한다.
#
# 잡는 것: "이름: 값"·"이름 = 값"·표의 "| 이름 | 값 |" 꼴의 비밀값(따옴표 친 JSON 키와 한글 이름 포함), IPv4, 전체 형태의 IPv6, 이메일,
#   AWS(액세스 키 ID, 라벨 없는 시크릿 키 추정, 계정 ID·ARN, ECR·RDS·ELB·EC2·S3 주소, sg-·vpc-·i- 같은 리소스 ID),
#   개인 키, JWT, 서비스 키(GitHub, Slack, Google, Stripe, 토스페이먼츠 등), Bearer·Basic 인증, 자격증명이 든 URL과 URL 쿼리의 토큰,
#   DB 접속 주소, 내부 호스트, 주민등록번호, 휴대전화 번호, 카드 번호.
# 못 잡는 것: 문장 속에 풀어 쓴 값, 압축형 IPv6, 실제 고객·판매자 데이터, 내부 시스템의 구체적인 이름, 영문자로만 된 비밀번호.
# 버전·빌드 표기("버전 25.0.1.2", "build 1.2.3.4")는 IP로 오인하지 않도록 단어와 함께 지운다.
# 예시 값(127.0.0.1, 0.0.0.0, @example.com, git@호스트:, host.docker.internal, ${변수} 참조, AWS 문서의 예시 키, 예시 전화번호 010-0000-0000, 자리표시자 <이름>, ****)은 그 값만 지우고 같은 줄의 나머지를 검사한다.
# 자리표시자는 안에 = : 따옴표가 없는 짧은 <...>만 지운다. 그렇지 않으면 < 와 > 사이의 비밀값까지 가려진다.
#
# set -e를 쓰지 않는 이유: grep이 일치가 없을 때 1을 돌려줘도 나머지 규칙과 파일을 계속 검사해야 한다.
# 사용법: scan-sensitive.sh <파일>...
set -uo pipefail
export LC_ALL=C

[ "$#" -ge 1 ] || { echo "사용법: scan-sensitive.sh <파일>..." >&2; exit 2; }

# 종류|grep 옵션|정규식 (grep -E)
rules=(
  'IP 주소||(^|[^0-9.A-Za-z])([0-9]{1,3}\.){3}[0-9]{1,3}(\.?([^0-9.]|$))'
  'IPv6 주소||([0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}'
  '이메일||[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
  'AWS 액세스 키||(AKIA|ASIA)[0-9A-Z]{16}'
  'AWS 계정 ID·ARN||arn:aws[a-z-]*:[a-z0-9-]+:[a-z0-9-]*:[0-9]{12}:|(^|[^0-9])[0-9]{12}\.dkr\.ecr\.'
  'AWS 리소스 ID||(^|[^A-Za-z0-9-])(sg|vpc|subnet|eni|vol|snap|ami|rtb|igw|nat|eipalloc)-[0-9a-f]{8,17}([^0-9a-z-]|$)|(^|[^A-Za-z0-9-])i-[0-9a-f]{8,17}([^0-9a-z]|$)'
  '서비스 키 형식||AIza[0-9A-Za-z_-]{35}|(sk|rk|pk)_(live|test)_[A-Za-z0-9]{16,}|(test|live)_(sk|gsk|ck)_[A-Za-z0-9]{16,}|SG\.[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}|hooks\.slack\.com/services/T[A-Z0-9]+/B[A-Z0-9]+/[A-Za-z0-9]+|npm_[A-Za-z0-9]{36}|glpat-[A-Za-z0-9_-]{20}'
  'Basic 인증||[Bb]asic[[:space:]]+[A-Za-z0-9+/=]{16,}'
  'URL 쿼리의 비밀값||[?&](access_token|refresh_token|token|api[_-]?key|apikey|secret|password|passwd|signature)=[A-Za-z0-9._~%+/=-]{8,}'
  'URL에 박힌 토큰||https?://[A-Za-z0-9_-]{20,}@'
  'DB 접속 주소||jdbc:[a-z0-9]+://[^/:[:space:]]*\.[^/:[:space:]]+'
  '주민등록번호||(^|[^0-9])[0-9]{6}-[1-4][0-9]{6}([^0-9]|$)'
  '휴대전화 번호||(^|[^0-9])01[016789]-[0-9]{3,4}-[0-9]{4}([^0-9]|$)'
  '카드 번호||(^|[^0-9-])([0-9]{4}[ -]){3}[0-9]{4}([^0-9-]|$)'
  '개인 키||-----BEGIN [A-Z ]*PRIVATE KEY-----'
  'JWT||eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}'
  '서비스 토큰||(^|[^A-Za-z0-9])(ghp|gho|ghs|github_pat)_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|(^|[^A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}'
  'Bearer 토큰||[Bb]earer[[:space:]]+[A-Za-z0-9._~+/=-]{16,}'
  '자격증명이 든 URL||://[^/[:space:]:@]+:[^/[:space:]@]+@'
  '내부 호스트||https?://[^/[:space:]]*\.(internal|corp|intra|local)([/:[:space:]]|$)|ip-([0-9]{1,3}-){3}[0-9]{1,3}\.|[A-Za-z0-9.-]+\.(rds|elb|elasticache|execute-api)\.[A-Za-z0-9.-]*amazonaws\.com|ec2-([0-9]{1,3}-){3}[0-9]{1,3}\.[A-Za-z0-9.-]*amazonaws\.com|[A-Za-z0-9.-]+\.s3[.-][A-Za-z0-9.-]*amazonaws\.com'
)

# 비밀값 대입. 이름 뒤에 구분자(: = |)가 오고 그 다음의 공백 없는 ASCII 덩어리를 값으로 본다. 이름과 구분자 사이의 따옴표는 허용한다.
# 값은 문장 끝 구두점과 백틱을 뗀 뒤 8자 이상이면서 숫자나 기호가 하나라도 있고 괄호가 없을 때만 잡는다. 괄호가 있으면 코드(메서드 호출)로 본다.
# "password: required."나 "token: 만료" 같은 설명과 `String password = encode(raw);` 같은 코드 예시는 지나간다.
# 안쪽 match()가 RSTART와 RLENGTH를 덮어쓰므로 바깥 값은 먼저 저장한다.
read -r -d '' SECRET_AWK <<'AWKEOF'
{
  line = $0; low = tolower(line); rest = low; off = 0
  while (match(rest, /(password|passwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|비밀번호|암호|토큰|시크릿)["']?[ \t]*[:=|][ \t]*["']?/)) {
    ms = RSTART; ml = RLENGTH
    start = off + ms + ml
    val = substr(line, start)
    if (match(val, /^[!-~]+/)) {
      v = substr(val, 1, RLENGTH)
      sub(/[.,;:)\]}"'`]+$/, "", v)
      if (length(v) >= 8 && v ~ /[^A-Za-z]/ && v !~ /[()]/) { print NR; next }
    }
    off += ms + ml - 1
    rest = substr(low, off + 1)
  }
}
AWKEOF

# 라벨 없는 AWS 시크릿 키 추정. 같은 줄에 aws, secret, credential, access(또는 시크릿, 액세스, 자격증명) 중 하나가 있고, 앞뒤가 이어진 문자가 아닌 40자 base64 덩어리에
# 대문자, 소문자, 숫자가 모두 있을 때만 잡는다. 40자 git 해시는 소문자 16진수라서 걸리지 않는다.
read -r -d '' AWS_SECRET_AWK <<'AWKEOF'
{
  low = tolower($0)
  if (low ~ /aws|secret|credential|access|시크릿|액세스|자격증명/) {
    s = $0; base = 0
    while (match(s, /[A-Za-z0-9\/+]{40}/)) {
      abs = base + RSTART
      t = substr($0, abs, 40)
      before = (abs > 1) ? substr($0, abs - 1, 1) : ""
      after = substr($0, abs + 40, 1)
      if (before !~ /[A-Za-z0-9\/+=]/ && after !~ /[A-Za-z0-9\/+=]/ && t ~ /[A-Z]/ && t ~ /[a-z]/ && t ~ /[0-9]/) { print NR; next }
      base = abs
      s = substr($0, base + 1)
    }
  }
}
AWKEOF

mask() {
  sed -E \
    -e 's/(버전|[Vv]ersion|VERSION|[Bb]uild|BUILD|빌드)( 번호)?[ :=vV]{0,3}([0-9]{1,3}\.){3}[0-9]{1,3}/ /g' \
    -e 's/(^|[^0-9.A-Za-z])(127\.0\.0\.1|0\.0\.0\.0)(\.?([^0-9.]|$))/\1 \3/g' \
    -e 's/@example\.(com|org|net)([^A-Za-z0-9.-]|$)/ \2/g' \
    -e 's/git@[A-Za-z0-9.-]+:/ /g' \
    -e 's/AKIAIOSFODNN7EXAMPLE|wJalrXUtnFEMI\/K7MDENG\/bPxRfiCYEXAMPLEKEY|AKIAX{16}/ /g' \
    -e 's/host\.docker\.internal/ /g' \
    -e 's/\$\{[A-Za-z0-9_.-]+\}/ /g' \
    -e 's/01[016789]-(0000-0000|1234-5678|[xX]{4}-[xX]{4})/ /g' \
    -e "s/<[^<>=:\"']{1,80}>/ /g" \
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
  lines="$(tr -d '\r' < "$f" | mask | mask | awk "$AWS_SECRET_AWK")"
  if [ -n "$lines" ]; then
    while IFS= read -r no; do hits+=("$no|AWS 시크릿 키 추정"); done <<< "$lines"
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
