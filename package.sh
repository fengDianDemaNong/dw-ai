#!/usr/bin/env bash
# 打全部已交付服务。可选参数 all|api|ui（默认 all）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
MODE="${1:-all}"
bash "$ROOT/dw-org/package.sh" "$MODE"
bash "$ROOT/dw-model/package.sh" "$MODE"
echo "==> 完成"
echo "    租户管理：$ROOT/dw-org/release/"
echo "    建模：    $ROOT/dw-model/release/"
