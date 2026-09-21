# 由 start/stop/status 引用。按本安装目录的 jar / 监听端口认进程，不写 pid 文件。
# shellcheck shell=bash

APP_JAR="${APP_JAR:-$ROOT/app/${APP_JAR_NAME:-dw-org-0.1.3.jar}}"

dwai_app_pids() {
  ps -ax -o pid=,args= 2>/dev/null | awk -v jar="$APP_JAR" '
    index($0, jar) && index($0, "com.dwai.platform.DwaiApplication") { print $1 }
  '
}

dwai_listen_pids() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null || true
  elif command -v ss >/dev/null 2>&1; then
    ss -lptn "sport = :$port" 2>/dev/null | awk '/pid=/ { if (match($0, /pid=[0-9]+/)) print substr($0, RSTART+4, RLENGTH-4) }'
  fi
}

dwai_unique_pids() {
  awk 'NF && !seen[$1]++ { print $1 }'
}

dwai_alive() {
  local pid="$1"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

dwai_stop_pid() {
  local pid="$1"
  dwai_alive "$pid" || return 0
  kill "$pid" 2>/dev/null || true
  local i
  for i in $(seq 1 20); do
    dwai_alive "$pid" || return 0
    sleep 0.3
  done
  kill -9 "$pid" 2>/dev/null || true
}
