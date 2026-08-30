#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="$ROOT/logs/api.pid"

if [[ ! -f "$PID_FILE" ]]; then
  echo "未发现运行中的进程"
  exit 0
fi

PID="$(cat "$PID_FILE")"
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  for _ in $(seq 1 20); do
    if ! kill -0 "$PID" 2>/dev/null; then
      break
    fi
    sleep 0.3
  done
  if kill -0 "$PID" 2>/dev/null; then
    kill -9 "$PID" 2>/dev/null || true
  fi
  echo "已停止 PID=$PID"
else
  echo "进程不存在 PID=$PID"
fi
rm -f "$PID_FILE"
