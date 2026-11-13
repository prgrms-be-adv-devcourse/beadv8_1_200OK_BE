#!/usr/bin/env bash
# launch.sh 가 시작한 앱 프로세스 트리, MySQL 컨테이너, 사본 트리만 정리한다. 증거 폴더(EVID)는 지우지 않는다.
# 사용: VD_STATE=<state 경로> scripts/cleanup.sh
set -uo pipefail
: "${VD_STATE:?VD_STATE 를 지정하세요}"
source "$VD_STATE"

descendants() { local p; for p in $(pgrep -P "$1" 2>/dev/null); do echo "$p"; descendants "$p"; done; }

if [ -n "${APP_PID:-}" ] && kill -0 "$APP_PID" 2>/dev/null; then
  PIDS="$(descendants "$APP_PID") $APP_PID"
  echo "앱 프로세스 종료: $PIDS"
  kill $PIDS 2>/dev/null
  for i in 1 2 3 4 5 6 7 8 9 10; do
    alive=""; for p in $PIDS; do kill -0 "$p" 2>/dev/null && alive="$alive $p"; done
    [ -z "$alive" ] && break; sleep 1
  done
  [ -n "${alive:-}" ] && { echo "강제 종료:$alive"; kill -9 $alive 2>/dev/null; }
fi

if [ -n "${CONTAINER_ID:-}" ]; then
  docker rm -f -v "$CONTAINER_ID" >/dev/null 2>&1 && echo "컨테이너 제거: $CONTAINER_ID"
elif [ -n "${CONTAINER_NAME:-}" ]; then
  # docker run 이 id 를 돌려주기 전에 실패한 경우. 우리가 지은 이름(vd-mysql-<실행 ID>)만 지운다
  docker rm -f -v "$CONTAINER_NAME" >/dev/null 2>&1 && echo "컨테이너 제거: $CONTAINER_NAME"
fi

[ -n "${WORK:-}" ] && [[ "$WORK" == *verify-delivery-work* ]] && rm -rf "$WORK" && echo "사본 트리 제거: $WORK"
echo "증거는 남김: $EVID"
