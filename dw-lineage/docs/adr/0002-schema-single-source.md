# ADR-0002: 建表脚本以 db/migration 为单一来源

## Status

Accepted（2026-09-21）

## Context

`dw-lineage` 引入 Flyway 后，同一份建表 DDL 同时存在于两处：

- `api/src/main/resources/db/migration/<方言>/V1__schema.sql`（Flyway 执行，打进 jar）
- `release/sql/<方言>/01_schema.sql`（安装包离线手工建库，`init-db.sh` 执行）

两份内容逐字节相同，靠人工同步。这个项目在早期就因此漂移过一次：安装包脚本少了
`meta_table` / `meta_column`，且血缘表停在旧结构 —— 而全新安装的环境看起来一切正常，
只有升上来的环境出问题。`TestSchema` 的注释里明确记录过这段教训。

注释还有第三条链路：`release/sql/comments.json` → `tools/gen-sql-comments.py` →
写进 `01_schema.sql` → 再手工同步到 `V1__schema.sql`。改一个字段注释要动三处。

## Decision

把**唯一权威来源收敛到 `db/migration/`**，其余位置一律派生：

1. `tools/gen-sql-comments.py` 的生成目标从 `release/sql/<方言>/01_schema.sql`
   改为 `db/migration/<方言>/V1__schema.sql`；
2. `tools/gen-data-dictionary.py` 改为读 `db/migration/h2/V1__schema.sql`；
3. **删除** `release/sql/<h2,mysql,postgresql>/01_schema.sql` 与 `02_init_data.sql`；
4. `build-release.sh` 在组装安装包时，从 `db/migration` 派生安装包内的
   `sql/<方言>/01_schema.sql` / `02_init_data.sql`（安装包内文件名保持不变，
   `init-db.sh`、`INSTALL.md`、`sql-cli.sh` 示例全部无需改动）；
5. `TestSchema` 转为读 `db/migration`，测试建的就是 Flyway 启动时执行的同一批脚本。

保留不动的：`00_create_database.sql`（建库，Flyway 不管）、`upgrade/`（离线升级兜底）、
`testdata/`、`03_query_examples.sql`、`comments.json`（注释维护入口）。

## Consequences

变得更容易：

- **改表结构只改一处**：`db/migration/<方言>/`。安装包脚本下次打包自动跟上，
  不再有「两份 DDL 漂移」这个口子；
- 改注释只改 `comments.json`，生成脚本一次写到三个方言的 Flyway 脚本；
- 测试与生产跑的是同一批脚本（`TestSchema` 读 `db/migration`），脚本写错整个套件立刻红。

变得更难 / 固有代价：

- **安装包脚本不再在源码树里可见**（`release/sql/<方言>/01_schema.sql` 只在打包产物里存在）。
  DBA 想提前审阅 DDL 时，需看 `db/migration/<方言>/V1__schema.sql`，或先执行一次打包；
- `build-release.sh` 多了一步派生逻辑，是「打包正确性」的新依赖点 ——
  已通过实际打包验证产物中三个方言的 01/02 均正确生成；
- 老库（用旧版 `release/sql` 建好、版本 < 1.0.5）仍需先按 `upgrade/` 升到 1.0.5，
  Flyway `baseline`（`baseline-version: 2`）才能正确接管 —— 这一前提已在
  `release/sql/upgrade/README.md` 写明。
