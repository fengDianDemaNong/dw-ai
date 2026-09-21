# 数据字典

> **本文件由 `tools/gen-data-dictionary.py` 生成，不要手工编辑。**
> 注释的唯一来源是 `release/sql/comments.json`，改完注释重跑生成脚本即可。
> 手写的字典和 DDL 必然漂移 —— 这个项目在「安装包脚本 vs 迁移脚本」上已经吃过一次亏。

类型以 H2 建表脚本为准；MySQL / PostgreSQL 的等价类型见各自的 `V1__schema.sql`，
三方言结构一致由 `MigrationDialectTest` 与 `MigrationExternalDbIT` 保证。

## 表一览

| 表 | 说明 |
|---|---|
| [`schema_version`](#schema_version) | 数据库结构版本记录。仅作历史版本信息留存；实际的建表与升级由 Flyway 的 flyway_schema_history 托管 |
| [`tenant`](#tenant) | 租户。数据隔离的最外层维度 |
| [`project`](#project) | 项目。租户之下的第二层隔离维度，业务数据都挂在项目上 |
| [`metadata_source`](#metadata_source) | 元数据服务配置。SQL 解析时按 priority 依次向这些来源要表结构。租户级共享 —— 同一租户下所有项目共用一份 |
| [`meta_table`](#meta_table) | 元数据目录中的表。这是数据库里真实存在的表结构，供 SQL 解析时查；与血缘表分开存储，避免推断结果污染事实 |
| [`meta_column`](#meta_column) | 元数据目录中的字段 |
| [`lineage_table`](#lineage_table) | 血缘中出现过的表。累积保存、不按版本切分；只放血缘必需的字段，中文名等描述属性归 meta_table |
| [`lineage_column`](#lineage_column) | 血缘中出现过的字段 |
| [`lineage_version`](#lineage_version) | 血缘版本。一个版本 = 某张目标表的一次血缘更新；版本号与 is_current 都在同一目标表内生效，解析不同的表互不影响 |
| [`lineage_edge`](#lineage_edge) | 列级血缘边。一条边 = 一个下游字段来自一个上游字段 |
| [`sync_job`](#sync_job) | 元数据导入任务。按 catalog / 库批量导入可能上万张表，同步请求必然超时，因此改成异步执行并记录进度 |
| [`data_catalog`](#data_catalog) | 本地元数据的数据目录。每个项目有且仅有一个默认目录，贴建表语句导入时没指定目录就落到它，因此 meta_table 里不存在没有数据目录的表 |
| [`temp_rule`](#temp_rule) | 临时库/表规则。命中的表在血缘图上会被穿透掉（上下游直接相连，而不是把图断成两段），保存血缘时一律过滤，库里永远不存临时表 |

## schema_version

数据库结构版本记录。仅作历史版本信息留存；实际的建表与升级由 Flyway 的 flyway_schema_history 托管

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|---|---|---|---|---|
| `version` | VARCHAR(32) | 否 |  | 已应用的结构版本号，如 1.0.3 |
| `description` | VARCHAR(256) | 是 |  | 该版本做了什么变更 |
| `applied_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 执行时间 |

## tenant

租户。数据隔离的最外层维度

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 |  | 主键 |
| `code` | VARCHAR(64) | 否 |  | 租户编码，全局唯一，创建后不可改 |
| `name` | VARCHAR(128) | 否 |  | 租户名称，页面展示用 |
| `status` | TINYINT | 否 | `1` | 1-启用 0-停用。停用后切换器里置灰，不做物理删除 |
| `created_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 更新时间 |

## project

项目。租户之下的第二层隔离维度，业务数据都挂在项目上

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 |  | 主键 |
| `tenant_id` | BIGINT | 否 |  | 所属租户 |
| `code` | VARCHAR(64) | 否 |  | 项目编码，租户内唯一，创建后不可改 |
| `name` | VARCHAR(128) | 否 |  | 项目名称 |
| `description` | VARCHAR(512) | 是 |  | 项目描述 |
| `status` | TINYINT | 否 | `1` | 1-启用 0-停用 |
| `created_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 更新时间 |

## metadata_source

元数据服务配置。SQL 解析时按 priority 依次向这些来源要表结构。租户级共享 —— 同一租户下所有项目共用一份

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 |  | 主键 |
| `tenant_id` | BIGINT | 否 |  | 所属租户 |
| `name` | VARCHAR(128) | 否 |  | 配置名称，租户内唯一 |
| `type` | VARCHAR(32) | 否 |  | GRAVITINO / DBX / CATALOG。CATALOG 是内置的本地元数据目录，不可删除 |
| `base_url` | VARCHAR(512) | 否 |  | 服务地址。CATALOG 类型为占位值 local://catalog，不会被真的访问 |
| `credential` | VARCHAR(1024) | 是 |  | 凭据密文（AES-256-GCM）。接口响应中永不回传明文，禁止明文入库 |
| `extra_config` | TEXT | 是 |  | 各类型的差异化配置，JSON。dbx 用它指定 connectionId / database / schema |
| `priority` | INT | 否 | `100` | 数字小的优先。多个来源时决定先问谁 |
| `enabled` | TINYINT | 否 | `1` | 1-启用 0-停用。停用的来源不参与解析 |
| `created_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 更新时间 |

## meta_table

元数据目录中的表。这是数据库里真实存在的表结构，供 SQL 解析时查；与血缘表分开存储，避免推断结果污染事实

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 |  | 主键 |
| `tenant_id` | BIGINT | 否 |  | 所属租户 |
| `project_id` | BIGINT | 否 |  | 所属项目 |
| `catalog_name` | VARCHAR(128) | 否 |  | 数据目录，指向 data_catalog.name。没指定时落到默认目录，1.0.4 起由 NOT NULL 强制 |
| `schema_name` | VARCHAR(128) | 否 |  | 库名，保留原始大小写供展示 |
| `table_name` | VARCHAR(256) | 否 |  | 表名，保留原始大小写供展示 |
| `full_name` | VARCHAR(512) | 否 |  | catalog.schema.table，恒为小写。唯一键与解析时的点查键 |
| `table_type` | VARCHAR(32) | 是 |  | 表类型：FULL 全量 / INCRE 增量 / SNAPSHOT_FULL 全量快照 / SNAPSHOT_INCRE 增量快照 / ZIPPER 拉链 / ARCH 归档 |
| `comment` | VARCHAR(512) | 是 |  | 表中文名 |
| `remark` | VARCHAR(1024) | 是 |  | 备注 |
| `db_type` | VARCHAR(32) | 是 |  | 该表所属的数据库方言 |
| `source` | VARCHAR(16) | 否 |  | 这行结构从哪来：DDL 建表语句 / GRAVITINO / DBX / MANUAL 手工维护 |
| `source_id` | BIGINT | 是 |  | 来自哪条 metadata_source；DDL 导入与手工录入时为空 |
| `synced_at` | TIMESTAMP | 是 |  | 最近一次从外部服务同步的时间 |
| `created_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 更新时间 |

## meta_column

元数据目录中的字段

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 |  | 主键 |
| `tenant_id` | BIGINT | 否 |  | 所属租户 |
| `project_id` | BIGINT | 否 |  | 所属项目 |
| `table_id` | BIGINT | 否 |  | 所属 meta_table |
| `column_name` | VARCHAR(256) | 否 |  | 字段名，保留原始大小写供展示 |
| `full_name` | VARCHAR(750) | 否 |  | catalog.schema.table.column，恒为小写 |
| `data_type` | VARCHAR(128) | 是 |  | 字段类型原文，如 decimal(12,2) |
| `comment` | VARCHAR(512) | 是 |  | 字段中文名 |
| `remark` | VARCHAR(1024) | 是 |  | 备注 |
| `ordinal` | INT | 否 | `0` | 字段顺序，从 1 开始 |
| `is_partition` | TINYINT | 否 | `0` | 1-分区字段 0-普通字段。Hive/Spark 的分区列对血缘是必需的 |
| `nullable` | TINYINT | 否 | `1` | 1-可空 0-非空 |
| `is_primary` | TINYINT | 否 | `0` | 1-主键 0-非主键 |
| `source` | VARCHAR(16) | 否 |  | 同 meta_table.source，字段可以被单独手工维护 |
| `created_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 更新时间 |

## lineage_table

血缘中出现过的表。累积保存、不按版本切分；只放血缘必需的字段，中文名等描述属性归 meta_table

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 |  | 主键 |
| `tenant_id` | BIGINT | 否 |  | 所属租户 |
| `project_id` | BIGINT | 否 |  | 所属项目 |
| `catalog_name` | VARCHAR(128) | 否 |  | 数据目录，取 full_name 的第一段。SQL 里没写目录时由解析层补默认目录，1.0.4 起由 NOT NULL 强制 |
| `schema_name` | VARCHAR(128) | 否 |  | 库名 |
| `table_name` | VARCHAR(256) | 否 |  | 表名 |
| `full_name` | VARCHAR(512) | 否 |  | catalog.schema.table，保留原始大小写（要显示在血缘图上）；与 meta_table 关联时对本侧加 lower() |
| `db_type` | VARCHAR(32) | 是 |  | 解析所用方言 |
| `is_temp` | TINYINT | 否 | `0` | 1-临时表或子查询产物 0-真实表 |
| `created_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 更新时间 |
| `table_type` | VARCHAR(32) | 是 |  | 表类型，保存血缘时从元数据来源快照下来；只有本地元数据目录有这个概念，远程来源为空 |
| `comment` | VARCHAR(512) | 是 |  | 表中文名，保存血缘时从解析所用的元数据来源快照下来。不与 meta_table 关联取——两侧的数据目录段天然对不上 |
| `remark` | VARCHAR(1024) | 是 |  | 备注，同上，快照自元数据来源 |

## lineage_column

血缘中出现过的字段

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 |  | 主键 |
| `tenant_id` | BIGINT | 否 |  | 所属租户 |
| `project_id` | BIGINT | 否 |  | 所属项目 |
| `table_id` | BIGINT | 否 |  | 所属 lineage_table |
| `column_name` | VARCHAR(256) | 否 |  | 字段名 |
| `full_name` | VARCHAR(750) | 否 |  | catalog.schema.table.column |
| `ordinal` | INT | 否 | `0` | 字段顺序。血缘侧的字段来自 SQL 解析，没有表定义里的列序，通常为 0 |
| `is_partition` | TINYINT | 否 | `0` | 1-分区字段 0-普通字段 |
| `created_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 更新时间 |
| `data_type` | VARCHAR(128) | 是 |  | 字段类型，保存血缘时快照自元数据来源。派生列（sum(x) as y）源表没有对应列，为空 |
| `comment` | VARCHAR(512) | 是 |  | 字段中文名，保存血缘时快照自元数据来源 |
| `remark` | VARCHAR(1024) | 是 |  | 备注，同上，快照自元数据来源 |

## lineage_version

血缘版本。一个版本 = 某张目标表的一次血缘更新；版本号与 is_current 都在同一目标表内生效，解析不同的表互不影响

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 |  | 主键 |
| `tenant_id` | BIGINT | 否 |  | 所属租户 |
| `project_id` | BIGINT | 否 |  | 所属项目 |
| `target_table_id` | BIGINT | 否 |  | 该版本属于哪张目标表。版本挂在目标表上，不是挂在项目上 |
| `version_no` | INT | 否 |  | 同一目标表内递增，从 1 开始 |
| `name` | VARCHAR(128) | 是 |  | 版本名称，可空 |
| `db_type` | VARCHAR(32) | 否 |  | 解析所用方言 |
| `sql_hash` | CHAR(64) | 否 |  | SQL 文本的 sha256，用于幂等去重 |
| `sql_text` | CLOB | 是 |  | 原始 SQL，供版本对比与算子下钻 |
| `is_current` | TINYINT | 否 | `0` | 1-当前版本。同一目标表内至多一条 |
| `stat_tables` | INT | 否 | `0` | 该版本涉及的表数量 |
| `stat_columns` | INT | 否 | `0` | 该版本涉及的字段数量 |
| `stat_edges` | INT | 否 | `0` | 该版本的列级边数量 |
| `created_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 创建时间 |

## lineage_edge

列级血缘边。一条边 = 一个下游字段来自一个上游字段

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 |  | 主键 |
| `tenant_id` | BIGINT | 否 |  | 所属租户 |
| `project_id` | BIGINT | 否 |  | 所属项目 |
| `version_id` | BIGINT | 否 |  | 所属版本。删除版本时按它批量清理 |
| `target_col_id` | BIGINT | 否 |  | 下游（被写入）字段 |
| `source_col_id` | BIGINT | 否 |  | 上游（来源）字段 |
| `transform` | TEXT | 是 |  | 转换表达式原文。目前尚未写入，见 docs/KNOWN_ISSUES.md |
| `is_cyclic` | TINYINT | 否 | `0` | 1-该边落在环上。环由解析时的 Tarjan 检测标记 |

## sync_job

元数据导入任务。按 catalog / 库批量导入可能上万张表，同步请求必然超时，因此改成异步执行并记录进度

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 |  | 主键 |
| `tenant_id` | BIGINT | 否 |  | 所属租户 |
| `project_id` | BIGINT | 否 |  | 所属项目。导入结果写进哪个项目的元数据目录 |
| `source_id` | BIGINT | 否 |  | 从哪条 metadata_source 导入 |
| `scope` | VARCHAR(16) | 否 |  | 导入范围：CATALOG 整个数据目录 / SCHEMA 整个库 / TABLE 指定的若干张表 |
| `source_catalog` | VARCHAR(128) | 是 |  | 源端数据目录（Gravitino 的 catalog） |
| `source_database` | VARCHAR(128) | 是 |  | 源端数据库（dbx 的 database） |
| `source_schema` | VARCHAR(128) | 是 |  | 源端库名。scope=CATALOG 时为空，由任务自己展开 |
| `connection_id` | VARCHAR(128) | 是 |  | dbx 中已保存的连接 id |
| `target_catalog` | VARCHAR(128) | 是 |  | 导入到哪个数据目录。为空时沿用源端。库名与表名不可改 —— 改了 SQL 解析按 schema.table 就对不上 |
| `tables` | TEXT | 是 |  | scope=TABLE 时要导入的表名清单，JSON 数组 |
| `overwrite_manual` | TINYINT | 否 | `0` | 1-覆盖人工维护过的内容 0-跳过它们 |
| `status` | VARCHAR(16) | 否 |  | PENDING 待执行 / RUNNING 执行中 / SUCCESS 全部成功 / PARTIAL 部分失败 / FAILED 整体失败 |
| `total` | INT | 否 | `0` | 待导入的表总数，展开完成后才有值 |
| `done` | INT | 否 | `0` | 已处理数量，用于进度条 |
| `created_cnt` | INT | 否 | `0` | 新增的表数 |
| `updated_cnt` | INT | 否 | `0` | 更新的表数 |
| `skipped_cnt` | INT | 否 | `0` | 跳过的表数（人工维护过且未勾选覆盖） |
| `failed_cnt` | INT | 否 | `0` | 失败的表数 |
| `message` | VARCHAR(1024) | 是 |  | 整体失败的原因摘要 |
| `failures` | TEXT | 是 |  | 失败明细，JSON 数组 |
| `started_at` | TIMESTAMP | 是 |  | 开始执行时间 |
| `finished_at` | TIMESTAMP | 是 |  | 结束时间 |
| `created_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 提交时间 |
| `updated_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 更新时间 |

## data_catalog

本地元数据的数据目录。每个项目有且仅有一个默认目录，贴建表语句导入时没指定目录就落到它，因此 meta_table 里不存在没有数据目录的表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 |  | 主键 |
| `tenant_id` | BIGINT | 否 |  | 所属租户 |
| `project_id` | BIGINT | 否 |  | 所属项目 |
| `name` | VARCHAR(128) | 否 |  | 目录名，项目内唯一，统一小写。它是表全名的第一段，因此不能含点号 |
| `is_default` | TINYINT | 否 | `0` | 1-默认目录 0-普通目录。每个项目最多一条为 1，由服务层保证；默认目录不允许删除 |
| `description` | VARCHAR(512) | 是 |  | 说明 |
| `created_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 更新时间 |

## temp_rule

临时库/表规则。命中的表在血缘图上会被穿透掉（上下游直接相连，而不是把图断成两段），保存血缘时一律过滤，库里永远不存临时表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|---|---|---|---|---|
| `id` | BIGINT | 否 |  | 主键 |
| `tenant_id` | BIGINT | 否 |  | 所属租户 |
| `project_id` | BIGINT | 否 |  | 所属项目 |
| `catalog_name` | VARCHAR(128) | 是 |  | 只对该数据目录生效；为空表示对该项目全部目录生效 |
| `target` | VARCHAR(16) | 否 |  | SCHEMA-匹配库名（整个库都算临时，如 tmp / test）TABLE-匹配表名（如 tmp_*） |
| `match_type` | VARCHAR(16) | 否 |  | GLOB-通配符 REGEX-正则。两者对 tmp_* 的解释完全不同：通配符下匹配 tmp_abc，正则下 * 修饰的是前一个字符 _，反而匹配不到 tmp_abc |
| `pattern` | VARCHAR(256) | 否 |  | 匹配表达式，不区分大小写 |
| `enabled` | TINYINT | 否 | `1` | 1-启用 0-停用。停用的规则不参与匹配 |
| `description` | VARCHAR(512) | 是 |  | 说明 |
| `created_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_at` | TIMESTAMP | 否 | `CURRENT_TIMESTAMP` | 更新时间 |
