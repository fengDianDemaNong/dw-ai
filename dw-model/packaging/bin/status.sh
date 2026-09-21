#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/conf/env.sh"
# shellcheck disable=SC1091
source "$(dirname "$0")/lib.sh"
PORT="${SERVER_PORT:-8080}"
APP_JAR="$ROOT/app/${APP_JAR_NAME:-dw-model-0.1.3.jar}"

pids="$(dwai_app_pids | dwai_unique_pids | tr '\n' ' ')"
pids="${pids%% }"
listen="$(dwai_listen_pids "$PORT" | dwai_unique_pids | tr '\n' ' ')"
listen="${listen%% }"

if [[ -n "$pids" ]]; then
  echo "运行中  本安装进程 ${pids}  端口 $PORT"
  if [[ -n "$listen" ]]; then
    echo "端口 $PORT 监听 PID ${listen}"
  fi
  if command -v curl >/dev/null 2>&1; then
    curl -fsS "http://127.0.0.1:${PORT}/api/health" && echo
  fi
  exit 0
fi

if [[ -n "$listen" ]]; then
  echo "本安装未找到 DwaiApplication，但端口 $PORT 已被占用 PID ${listen}" >&2
  exit 1
fi

echo "未运行"
exit 1
