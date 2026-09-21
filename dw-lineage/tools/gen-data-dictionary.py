#!/usr/bin/env python3
"""从 comments.json + 建表脚本生成 docs/DATA_DICTIONARY.md。

从单一来源生成而不是手写：手写的字典与 DDL 必然漂移，这个项目已经吃过一次亏。
类型与可空信息从 H2 建表脚本里解析（三方言结构一致，由 MigrationDialectTest 保证）。
"""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
COMMENTS = json.loads((ROOT / "release/sql/comments.json").read_text(encoding="utf-8"))
SCHEMA = (ROOT / "sql-tools/src/main/resources/db/migration/h2/V1__schema.sql").read_text(encoding="utf-8")


def parse_columns(table: str):
    """从 CREATE TABLE 块里解析出 (列名, 类型, 是否可空, 默认值)。"""
    m = re.search(r"CREATE TABLE " + table + r"\s*\((.*?)\n\);", SCHEMA, re.S)
    if not m:
        return []
    out = []
    for line in m.group(1).split("\n"):
        line = line.strip().rstrip(",")
        if not line or line.startswith("--"):
            continue
        if re.match(r"^(PRIMARY KEY|CONSTRAINT|UNIQUE|KEY|INDEX)\b", line, re.I):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        name = parts[0].strip('`"')
        rest = " ".join(parts[1:])
        type_m = re.match(r"([A-Z]+(?:\([\d,\s]+\))?)", rest, re.I)
        col_type = type_m.group(1) if type_m else parts[1]
        nullable = "否" if "NOT NULL" in rest.upper() else "是"
        default_m = re.search(r"DEFAULT\s+([^\s,]+(?:\s+[^\s,]+)*?)(?:\s+(?:NOT NULL|AUTO_INCREMENT))?$",
                              rest, re.I)
        default = default_m.group(1) if default_m else ""
        if default.upper() == "NULL":
            default = ""
        out.append((name, col_type, nullable, default))
    return out


lines = [
    "# 数据字典",
    "",
    "> **本文件由 `tools/gen-data-dictionary.py` 生成，不要手工编辑。**",
    "> 注释的唯一来源是 `release/sql/comments.json`，改完注释重跑生成脚本即可。",
    "> 手写的字典和 DDL 必然漂移 —— 这个项目在「安装包脚本 vs 迁移脚本」上已经吃过一次亏。",
    "",
    "类型以 H2 建表脚本为准；MySQL / PostgreSQL 的等价类型见各自的 `V1__schema.sql`，",
    "三方言结构一致由 `MigrationDialectTest` 与 `MigrationExternalDbIT` 保证。",
    "",
    "## 表一览",
    "",
    "| 表 | 说明 |",
    "|---|---|",
]

tables = {k: v for k, v in COMMENTS.items() if not k.startswith("_")}
for table, cols in tables.items():
    lines.append(f"| [`{table}`](#{table}) | {cols['_table']} |")

for table, cols in tables.items():
    lines += ["", f"## {table}", "", cols["_table"], "",
              "| 字段 | 类型 | 可空 | 默认值 | 说明 |", "|---|---|---|---|---|"]
    parsed = {c[0]: c for c in parse_columns(table)}
    for col, text in cols.items():
        if col.startswith("_"):
            continue
        c = parsed.get(col)
        col_type = c[1] if c else ""
        nullable = c[2] if c else ""
        default = f"`{c[3]}`" if c and c[3] else ""
        lines.append(f"| `{col}` | {col_type} | {nullable} | {default} | {text} |")
    missing = set(parsed) - set(cols)
    if missing:
        lines.append("")
        lines.append(f"> ⚠️ 建表脚本里有但 comments.json 未描述的字段：{sorted(missing)}")

out = ROOT / "docs" / "DATA_DICTIONARY.md"
out.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(f"已生成 {out}，共 {len(tables)} 张表")
