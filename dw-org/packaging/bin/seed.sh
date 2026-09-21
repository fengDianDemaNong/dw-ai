#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/conf/env.sh"

if ! command -v java >/dev/null 2>&1; then
  echo "未找到 java，请安装 JDK/JRE 17+" >&2
  exit 1
fi

export DW_AI_HOME="${DW_AI_HOME:-$ROOT}"
export DB_URL="${DB_URL:-}"
export DB_USER="${DB_USER:-}"
export DB_PASSWORD="${DB_PASSWORD:-}"
export DB_TYPE="${DB_TYPE:-}"

APP_JAR="$ROOT/app/${APP_JAR_NAME:-dw-org-0.1.3.jar}"
if [[ ! -f "$APP_JAR" ]]; then
  echo "未找到 $APP_JAR" >&2
  exit 1
fi

java -cp "$APP_JAR:$ROOT/libs/*" com.dwai.platform.SeedMain
