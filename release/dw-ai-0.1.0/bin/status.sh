#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/conf/env.sh"
PID_FILE="$ROOT/logs/api.pid"
PORT="${SERVER_PORT:-8080}"

if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "运行中 PID=$(cat "$PID_FILE")  端口 $PORT"
  if command -v curl >/dev/null 2>&1; then
    curl -fsS "http://127.0.0.1:${PORT}/api/health" && echo
  fi
  exit 0
fi
echo "未运行"
exit 1
