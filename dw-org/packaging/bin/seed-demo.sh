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
# 下面三个在 conf/env.sh 里只是赋值、没有 export，子进程继承不到 —— 这里补上。
# 用 ${VAR:-} 而不是裸 "$VAR"：model 的 env.sh 里没有 DW_AI_RUN_MODE，set -u 下裸引用会直接退。
export DW_AI_MODE="${DW_AI_MODE:-}"
export DW_AI_RUN_MODE="${DW_AI_RUN_MODE:-}"
# H2 库名：必须与服务读的 dwai.db-file 一致。两边都来自同一个 conf/env.sh，所以别在这里另写死值。
export DWAI_DB_FILE="${DWAI_DB_FILE:-}"
# 本安装包是组织平台（恒 multi），身份数据在这里是权威数据而非演示数据：
# 决定 SeedMain 在 multi 下是否跳过身份层 —— 组织不跳过，仓建设跳过（身份由组织扇出）。
export DW_AI_SEED_IS_ORG=true

APP_JAR="$ROOT/app/${APP_JAR_NAME:-dw-org-0.1.3.jar}"
if [[ ! -f "$APP_JAR" ]]; then
  echo "未找到 $APP_JAR" >&2
  exit 1
fi

# SeedMain 在 dw-common 里（组织与仓建设共用同一个入口），故包名是 platform.meta.seed。
java -cp "$APP_JAR:$ROOT/libs/*" com.dwai.platform.meta.seed.SeedMain
