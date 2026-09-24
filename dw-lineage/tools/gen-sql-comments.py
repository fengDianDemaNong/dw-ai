#!/usr/bin/env python3
"""把 release/sql/comments.json 里的注释生成到 Flyway 迁移脚本（db/migration）里。

为什么要生成而不是手写：同一份注释要在 H2 / MySQL / PostgreSQL 三处出现，
三份手工维护必然漂移。改注释只改 comments.json，然后重跑本脚本。

建表脚本的单一来源是 `db/migration/{方言}/V1__schema.sql`（Flyway 权威）。
安装包里 `release/sql/{方言}/01_schema.sql` 只是它的派生产物，由 package.sh 的
derive_sql() 在打包时复制生成，不在此维护。

MySQL 用列内联 COMMENT，H2 与 PostgreSQL 用 COMMENT ON 语句追加在建表之后。
"""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
MIGRATION = ROOT / "api" / "src" / "main" / "resources" / "db" / "migration"
COMMENTS = json.loads((ROOT / "release" / "sql" / "comments.json").read_text(encoding="utf-8"))
TABLES = {k: v for k, v in COMMENTS.items() if not k.startswith("_")}

MARK_BEGIN = "-- >>> 表与字段注释（由 tools/gen-sql-comments.py 生成，勿手工编辑）"
MARK_END = "-- <<< 注释结束"


def esc(text: str) -> str:
    return text.replace("'", "''")


def strip_generated(sql: str) -> str:
    """去掉上一次生成的注释块，保证可重复执行。"""
    pattern = re.compile(re.escape(MARK_BEGIN) + r".*?" + re.escape(MARK_END) + r"\n?", re.S)
    return pattern.sub("", sql).rstrip() + "\n"


def comment_on_block() -> str:
    """H2 / PostgreSQL 通用的 COMMENT ON 语句。"""
    lines = [MARK_BEGIN]
    for table, cols in TABLES.items():
        lines.append("")
        lines.append(f"COMMENT ON TABLE {table} IS '{esc(cols['_table'])}';")
        for col, text in cols.items():
            if col.startswith("_"):
                continue
            lines.append(f"COMMENT ON COLUMN {table}.{col} IS '{esc(text)}';")
    lines.append("")
    lines.append(MARK_END)
    return "\n".join(lines) + "\n"


def apply_mysql_inline(sql: str) -> str:
    """MySQL：把注释写进列定义与表尾。逐表逐列做正则替换。"""
    for table, cols in TABLES.items():
        # 定位该表的 CREATE TABLE 块
        m = re.search(r"(CREATE TABLE `?" + table + r"`?\s*\((?:[^;])*?\n\)[^;]*;)", sql, re.S)
        if not m:
            print(f"  ! MySQL 里没找到表 {table}，跳过")
            continue
        block = m.group(1)
        new_block = block

        for col, text in cols.items():
            if col.startswith("_"):
                continue
            # 只改列定义行：行首若干空格 + 列名 + 类型…，且该行还没有 COMMENT
            col_re = re.compile(
                r"^(\s+`?" + col + r"`?\s+(?![A-Z]*KEY\b).*?)(,?)$",
                re.M)

            def add(match):
                body, comma = match.group(1).rstrip(), match.group(2)
                if "COMMENT" in body.upper():
                    body = re.sub(r"\s+COMMENT\s+'(?:[^']|'')*'", "", body, flags=re.I)
                return f"{body} COMMENT '{esc(text)}'{comma}"

            new_block, n = col_re.subn(add, new_block, count=1)

        # 表级注释：替换已有的 COMMENT='...' 或追加
        if re.search(r"COMMENT='(?:[^']|'')*';", new_block):
            new_block = re.sub(r"COMMENT='(?:[^']|'')*';",
                               f"COMMENT='{esc(cols['_table'])}';", new_block)
        else:
            new_block = new_block.rstrip().rstrip(";") + f" COMMENT='{esc(cols['_table'])}';"

        sql = sql.replace(block, new_block)
    return sql


def main():
    for dialect in ("h2", "postgresql"):
        path = MIGRATION / dialect / "V1__schema.sql"
        sql = strip_generated(path.read_text(encoding="utf-8"))
        path.write_text(sql + "\n" + comment_on_block(), encoding="utf-8")
        print(f"{dialect}: 已写入 {sum(len(c) for c in TABLES.values())} 条注释")

    path = MIGRATION / "mysql" / "V1__schema.sql"
    sql = strip_generated(path.read_text(encoding="utf-8"))
    path.write_text(apply_mysql_inline(sql), encoding="utf-8")
    print("mysql: 已写入内联注释")


if __name__ == "__main__":
    main()
