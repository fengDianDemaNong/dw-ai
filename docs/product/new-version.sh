#!/usr/bin/env bash
set -euo pipefail
FROM="${1:-}"
TO="${2:-}"
if [[ -z "$FROM" || -z "$TO" ]]; then
  echo "用法: ./docs/product/new-version.sh 0.1.0 0.2.0"
  exit 1
fi
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/docs/product/versions/$FROM"
DST="$ROOT/docs/product/versions/$TO"
if [[ ! -d "$SRC" ]]; then
  echo "源版本不存在: $FROM"
  exit 1
fi
if [[ -d "$DST" ]]; then
  echo "目标版本已存在: $TO"
  exit 1
fi
cp -R "$SRC" "$DST"

minor="${TO#*.}"
minor="${minor%%.*}"
port=$((4172 + 10#${minor:-1}))

python3 - <<PY
from pathlib import Path
root = Path(r"$DST")
old, new, port = "$FROM", "$TO", "$port"
for p in root.rglob("*"):
    if not p.is_file():
        continue
    if p.suffix.lower() not in {".md", ".ts", ".vue", ".html", ".json", ".mjs", ".js"}:
        continue
    text = p.read_text(encoding="utf-8")
    updated = text.replace(old, new).replace(f"dw-ai.proto.{old}", f"dw-ai.proto.{new}")
    if p.name == "vite.config.ts":
        updated = updated.replace("port: 4173", f"port: {port}")
        # 若源版本不是 4173，仍强制写成新端口
        import re
        updated = re.sub(r"port:\s*\d+", f"port: {port}", updated, count=1)
    if updated != text:
        p.write_text(updated, encoding="utf-8")
PY

echo "已创建 docs/product/versions/$TO"
echo "书面规范: $DST/spec"
echo "交互原型: npm run proto -- $TO  （端口 $port）"
