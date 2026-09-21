#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/conf/env.sh"
# shellcheck disable=SC1091
source "$(dirname "$0")/lib.sh"
PORT="${SERVER_PORT:-8080}"
APP_JAR="$ROOT/app/${APP_JAR_NAME:-dw-org-0.1.3.jar}"

pids=""
while read -r p; do
  [[ -n "$p" ]] && pids="$pids $p"
done < <(dwai_app_pids | dwai_unique_pids)

if [[ -z "${pids// /}" ]]; then
  while read -r lp; do
    [[ -z "$lp" ]] && continue
    cmd="$(ps -p "$lp" -o args= 2>/dev/null || true)"
    if [[ "$cmd" == *"$APP_JAR"* ]]; then
      pids="$pids $lp"
    fi
  done < <(dwai_listen_pids "$PORT")
fi

pids="${pids# }"
if [[ -z "$pids" ]]; then
  echo "未发现本安装的运行进程"
  exit 0
fi

for pid in $pids; do
  dwai_stop_pid "$pid"
done
echo "已停止 PID $pids"
