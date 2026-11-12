# 도우미 스크립트 공용. VD_STATE 의 상태 파일을 읽는다
: "${VD_STATE:?VD_STATE=<launch.sh 가 출력한 state 경로> 를 지정하세요}"
# shellcheck disable=SC1090
source "$VD_STATE"
# -e 조회만 하므로 docker exec 에 -i 를 주지 않는다. -i 를 주면 호출자의 표준입력(파이프, heredoc)을 소비해 뒤의 입력이 사라진다.
# 표준입력으로 SQL 파일을 넘기는 시드는 seed-shipments.sh 가 자기 docker exec -i 로 직접 한다
mysql_exec() { docker exec "$CONTAINER_NAME" mysql -uroot -p"$ROOT_PW" --default-character-set=utf8mb4 "$@" 2>/dev/null; }
