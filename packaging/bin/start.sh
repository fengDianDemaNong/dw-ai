#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/conf/env.sh"
# shellcheck disable=SC1091
source "$(dirname "$0")/lib.sh"

if ! command -v java >/dev/null 2>&1; then
  echo "未找到 java，请安装 JDK/JRE 17+" >&2
  exit 1
fi

mkdir -p "$ROOT/logs" "$ROOT/data"
rm -f "$ROOT/logs/api.pid"

APP_JAR="$ROOT/app/dw-ai-api.jar"
if [[ ! -f "$APP_JAR" ]]; then
  echo "未找到 $APP_JAR" >&2
  exit 1
fi

export SERVER_PORT="${SERVER_PORT:-8080}"
running="$(dwai_app_pids | dwai_unique_pids | tr '\n' ' ')"
running="${running%% }"
if [[ -n "$running" ]]; then
  echo "已在运行 PID ${running}"
  exit 0
fi

busy="$(dwai_listen_pids "$SERVER_PORT" | dwai_unique_pids | tr '\n' ' ')"
busy="${busy%% }"
if [[ -n "$busy" ]]; then
  echo "端口 ${SERVER_PORT} 已被占用 PID ${busy}，请先停止占用方或改 conf/env.sh 的 SERVER_PORT" >&2
  exit 1
fi

export DW_AI_HOME="$ROOT"
export DB_URL="${DB_URL:-}"
export DB_USER="${DB_USER:-}"
export DB_PASSWORD="${DB_PASSWORD:-}"
export DB_TYPE="${DB_TYPE:-}"
export SECURITY_MODE JWT_SECRET ALLOW_DEV_LOGIN
export CASDOOR_ISSUER CASDOOR_JWK CASDOOR_CLIENT_ID CASDOOR_ORG_MAP
export SR_ENABLED SR_URL SR_USER SR_PASSWORD
export DS_ENABLED DS_URL DS_TOKEN DS_PROJECT_CODE
export CORS_ORIGINS WEB_STATIC_DIR
export DW_AI_MODE DW_AI_LLM_SECRET
export BOOTSTRAP_ADMIN_USER BOOTSTRAP_ADMIN_PASSWORD BOOTSTRAP_ADMIN_NAME
WEB_DIR="${WEB_STATIC_DIR:-$ROOT/web}"
export WEB_STATIC_DIR="$WEB_DIR"

nohup java -cp "$APP_JAR:$ROOT/libs/*" com.dwai.platform.DwaiApplication \
  --spring.config.additional-location="optional:file:$ROOT/conf/" \
  --server.port="$SERVER_PORT" \
  --logging.file.name="$ROOT/logs/dw-ai-api.log" \
  --dwai.web.static-dir="$WEB_DIR" \
  --spring.web.resources.static-locations="file:${WEB_DIR}/" \
  >"$ROOT/logs/startup.out" 2>&1 &
disown $! 2>/dev/null || true

HEALTH="http://127.0.0.1:${SERVER_PORT}/api/health"
WAIT_SECS="${START_WAIT_SECS:-180}"
echo "正在启动，等待健康检查（最多 ${WAIT_SECS}s）…"
i=0
while (( i < WAIT_SECS )); do
  live="$(dwai_app_pids | dwai_unique_pids | tr '\n' ' ')"
  live="${live%% }"
  if [[ -z "$live" && i -ge 2 ]]; then
    echo "进程已退出，启动失败。日志末尾：" >&2
    tail -n 40 "$ROOT/logs/dw-ai-api.log" 2>/dev/null >&2 || tail -n 40 "$ROOT/logs/startup.out" >&2
    exit 1
  fi
  if command -v curl >/dev/null 2>&1; then
    if curl -fsS "$HEALTH" >/dev/null 2>&1; then
      echo "已启动 PID ${live}  控制台 http://127.0.0.1:${SERVER_PORT}/"
      echo "平台管理员 admin / admin123（空库首次启动自动创建，请改密）"
      exit 0
    fi
  elif [[ $i -ge 8 && -n "$live" ]]; then
    echo "已启动 PID ${live}  控制台 http://127.0.0.1:${SERVER_PORT}/（本机无 curl，未做健康检查）"
    echo "平台管理员 admin / admin123（空库首次启动自动创建，请改密）"
    exit 0
  fi
  sleep 1
  i=$((i + 1))
done

echo "等待超时（${WAIT_SECS}s），健康检查未通过。日志末尾：" >&2
tail -n 40 "$ROOT/logs/dw-ai-api.log" 2>/dev/null >&2 || tail -n 40 "$ROOT/logs/startup.out" >&2
exit 1
