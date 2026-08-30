#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/conf/env.sh"

if ! command -v java >/dev/null 2>&1; then
  echo "未找到 java，请安装 JDK/JRE 17+" >&2
  exit 1
fi

mkdir -p "$ROOT/logs"
PID_FILE="$ROOT/logs/api.pid"
if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "已在运行 PID=$(cat "$PID_FILE")"
  exit 0
fi

export DB_URL DB_USER DB_PASSWORD
export SECURITY_MODE JWT_SECRET ALLOW_DEV_LOGIN
export CASDOOR_ISSUER CASDOOR_JWK CASDOOR_CLIENT_ID CASDOOR_ORG_MAP
export SR_ENABLED SR_URL SR_USER SR_PASSWORD
export DS_ENABLED DS_URL DS_TOKEN DS_PROJECT_CODE
export CORS_ORIGINS WEB_STATIC_DIR
export SERVER_PORT="${SERVER_PORT:-8080}"
WEB_DIR="${WEB_STATIC_DIR:-$ROOT/libs/web}"
export WEB_STATIC_DIR="$WEB_DIR"

nohup java -jar "$ROOT/libs/dw-ai-api-0.1.0.jar" \
  --spring.config.additional-location="optional:file:$ROOT/conf/" \
  --server.port="$SERVER_PORT" \
  --logging.file.name="$ROOT/logs/dw-ai-api.log" \
  --dwai.web.static-dir="$WEB_DIR" \
  --spring.web.resources.static-locations="file:${WEB_DIR}/" \
  >"$ROOT/logs/startup.out" 2>&1 &
echo $! >"$PID_FILE"
echo "已启动 PID=$(cat "$PID_FILE")  控制台 http://127.0.0.1:${SERVER_PORT}/"
