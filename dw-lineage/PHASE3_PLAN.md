
# 第三期（能力升级）详细方案 — 待确认

> 前置：[PHASE1_REPORT.md](PHASE1_REPORT.md)、[PHASE2_REPORT.md](PHASE2_REPORT.md)
> 需求来源：[PHASE3_BACKLOG.md](PHASE3_BACKLOG.md)
> 本期目标：**从「一次性即席解析器」升级为「可沉淀、可追溯、多租户的血缘平台」**
> 预估：6~8 周

---

## 一、本期做什么

| # | 任务 | 来源 | 优先级 |
|---|---|---|---|
| P1 | 数据模型：多租户 + 血缘持久化 + 多版本 | 你明确要求 | ★★★ 本期地基 |
| P2 | 持久化多数据库支持（本地 / MySQL / PG） | 你明确要求 | ★★★ |
| P3 | 血缘版本管理（默认最新、可切换、可删除） | 你明确要求 | ★★★ |
| P4 | 元数据服务接入（Gravitino + dbx）+ 配置页面 | 你明确要求 | ★★★ |
| P5 | 全局血缘图查询（上溯 / 影响分析） | 持久化的价值兑现 | ★★☆ |
| P6 | 转换逻辑（算子表达式）下钻 | 一期发现被丢弃 | ★★☆ |
| P7 | 可观测性 | 二期顺延 | ★☆☆ |

**P1 与 P2、P3 必须一起设计**：多租户会改动每一张表，不能先做完持久化再补租户。

---

## 二、P1 数据模型设计

### 2.1 分层

```
tenant（租户）
  └── project（项目）
        ├── metadata_source     元数据服务配置（Gravitino / dbx）
        └── lineage_version     血缘版本
              ├── lineage_table    表
              ├── lineage_column   列
              └── lineage_edge     列级依赖边
```

**所有业务表都带 `tenant_id` + `project_id`**，查询强制带租户过滤。

### 2.2 表结构（以 MySQL 方言示意，三种数据库各有一份等价脚本）

```sql
-- ---------- 租户与项目 ----------
CREATE TABLE tenant (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code         VARCHAR(64)  NOT NULL COMMENT '租户编码，全局唯一',
    name         VARCHAR(128) NOT NULL,
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (code)
) COMMENT='租户';

CREATE TABLE project (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL,
    code         VARCHAR(64)  NOT NULL COMMENT '项目编码，租户内唯一',
    name         VARCHAR(128) NOT NULL,
    description  VARCHAR(512) DEFAULT NULL,
    status       TINYINT      NOT NULL DEFAULT 1,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project (tenant_id, code),
    KEY idx_project_tenant (tenant_id)
) COMMENT='项目';

-- ---------- 血缘版本 ----------
CREATE TABLE lineage_version (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    project_id    BIGINT       NOT NULL,
    version_no    INT          NOT NULL COMMENT '项目内递增，从 1 开始',
    name          VARCHAR(128) DEFAULT NULL COMMENT '版本名，便于人工识别',
    db_type       VARCHAR(32)  NOT NULL COMMENT '解析所用方言',
    sql_hash      CHAR(64)     NOT NULL COMMENT 'SQL 文本的 sha256，用于幂等去重',
    sql_text      MEDIUMTEXT   COMMENT '原始 SQL，供版本对比与算子下钻',
    is_current    TINYINT      NOT NULL DEFAULT 0 COMMENT '1-当前版本，项目内至多一条',
    stat_tables   INT          NOT NULL DEFAULT 0,
    stat_columns  INT          NOT NULL DEFAULT 0,
    stat_edges    INT          NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_version (tenant_id, project_id, version_no),
    KEY idx_version_current (tenant_id, project_id, is_current),
    KEY idx_version_hash (tenant_id, project_id, sql_hash)
) COMMENT='血缘版本';

-- ---------- 血缘数据 ----------
CREATE TABLE lineage_table (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL,
    project_id   BIGINT       NOT NULL,
    version_id   BIGINT       NOT NULL,
    catalog_name VARCHAR(128) DEFAULT NULL,
    schema_name  VARCHAR(128) NOT NULL,
    table_name   VARCHAR(256) NOT NULL,
    full_name    VARCHAR(512) NOT NULL COMMENT '[catalog.]schema.table，查询用',
    is_temp      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否临时表/子查询产物',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_table (version_id, full_name),
    KEY idx_table_lookup (tenant_id, project_id, full_name)
) COMMENT='血缘中的表';

CREATE TABLE lineage_column (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL,
    project_id   BIGINT       NOT NULL,
    version_id   BIGINT       NOT NULL,
    table_id     BIGINT       NOT NULL,
    column_name  VARCHAR(256) NOT NULL,
    full_name    VARCHAR(768) NOT NULL COMMENT 'schema.table.column',
    ordinal      INT          NOT NULL DEFAULT 0,
    is_partition TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_column (version_id, full_name),
    KEY idx_column_table (table_id),
    KEY idx_column_lookup (tenant_id, project_id, full_name)
) COMMENT='血缘中的列';

CREATE TABLE lineage_edge (
    id             BIGINT     NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT     NOT NULL,
    project_id     BIGINT     NOT NULL,
    version_id     BIGINT     NOT NULL,
    target_col_id  BIGINT     NOT NULL COMMENT '下游（被写入）列',
    source_col_id  BIGINT     NOT NULL COMMENT '上游（来源）列',
    transform      TEXT       COMMENT '转换表达式，见 P6',
    is_cyclic      TINYINT    NOT NULL DEFAULT 0 COMMENT '是否落在环上',
    PRIMARY KEY (id),
    UNIQUE KEY uk_edge (version_id, target_col_id, source_col_id),
    KEY idx_edge_target (version_id, target_col_id),
    KEY idx_edge_source (version_id, source_col_id)
) COMMENT='列级血缘边';
```

### 2.3 几个设计取舍，需要说明

**为什么边表存 `col_id` 而不是列全名**
图遍历要反复 join，整型主键比 768 字符的字符串快得多，索引也小。
代价是写入时要先落列再落边，多一次映射。

**为什么保留 `full_name` 冗余字段**
「按名字查血缘」是最高频入口（用户输入 `dws.t.c` 查上下游），
有它就能一次命中索引，不必先解析再多表 join。

**为什么边表不做外键约束**
删除版本时要级联删几十万行，外键会显著拖慢；改为应用层按 `version_id` 批量删除。
三种数据库对级联删除的行为也不完全一致，不依赖它更可控。

**是否需要 `lineage_table_rel`（表级边）**
不建单独的表。表级血缘可由列级边聚合得到；单独存会引入一致性问题。
若后续查询性能不足，再加物化视图或汇总表。

---

## 三、P2 多数据库支持

### 3.1 三种后端

| 场景 | 选型 | 理由 |
|---|---|---|
| 本地/嵌入式 | **H2（file 模式）** | 纯 Java 无需外部进程，Spring Boot 原生支持，**支持递归 CTE**，开箱即用 |
| 生产 | **MySQL 8.0+** | 递归 CTE 需要 8.0，5.7 不支持，需在文档写清 |
| 生产 | **PostgreSQL 12+** | 递归 CTE 支持最完善 |

> ✅ **已确认选 H2**。也评估过 SQLite：需额外 JDBC 驱动，并发写能力弱，
> 且部分 DDL（如 `ALTER`）受限，对「本地也要能长期存血缘」的场景不如 H2。
>
> H2 需注意：使用 `2.x` 版本（1.4.x 的递归 CTE 有缺陷）；
> file 模式下要显式配置 `AUTO_SERVER=TRUE` 或确保单进程访问，避免文件锁冲突。

### 3.2 迁移脚本管理

引入 **Flyway**，按方言分目录：

```
src/main/resources/db/migration/
├── common/     三种数据库通用的 DML（如初始化默认租户）
├── h2/         V1__init.sql ...
├── mysql/      V1__init.sql ...
└── postgresql/ V1__init.sql ...
```

`spring.flyway.locations` 按激活的 profile 指向对应目录。
选 Flyway 而非 Liquibase：脚本就是原生 SQL，三套方言差异一目了然，
不必再学一层抽象 DSL。

### 3.3 访问层

✅ **已确认：Spring Data JPA（实体 CRUD） + JdbcTemplate 原生 SQL（图遍历）**

- 实体 CRUD 用 JPA，省掉大量样板，且 `jakarta.persistence-api` 已在 pom 中
- 图遍历必须写递归 CTE，JPA 表达不了，用 `JdbcTemplate` 直接写
- 递归 CTE 在 H2 / MySQL 8 / PG 语法基本一致（`WITH RECURSIVE`），
  差异点（如 PG 需要 `UNION ALL` 显式去重）用少量方言适配类隔离

```java
public interface LineageRepository {
    long saveGraph(LineageContext ctx, LineageGraph graph);
    List<LineageEdgeRow> upstream(LineageContext ctx, String columnFullName, int depth);
    List<LineageEdgeRow> downstream(LineageContext ctx, String columnFullName, int depth);
}
```

### 3.4 上溯查询示意（三方言通用）

```sql
WITH RECURSIVE up(col_id, depth) AS (
    SELECT c.id, 0 FROM lineage_column c
     WHERE c.tenant_id = ? AND c.project_id = ? AND c.version_id = ? AND c.full_name = ?
    UNION ALL
    SELECT e.source_col_id, up.depth + 1
      FROM lineage_edge e
      JOIN up ON e.target_col_id = up.col_id
     WHERE e.version_id = ? AND up.depth < ?
)
SELECT * FROM up;
```

> 环在写入时已由二期的 Tarjan 检测标记（`is_cyclic`），
> 但递归查询仍需 `depth` 上限兜底，防止数据异常导致无限递归。

---

## 四、P3 血缘版本

### 4.1 规则

- 每次解析并保存 → 新建一个 `lineage_version`，`version_no` 项目内递增
- **默认查询最新版本**（`version_no` 最大者）
- 用户可把某个版本「设为当前」（`is_current=1`，项目内互斥），查询优先用它
- 可删除任意版本；删除当前版本后自动回退到最新的剩余版本
- `sql_hash` 幂等：同一段 SQL 重复解析默认不新建版本，可用参数强制新建

### 4.2 接口

| 接口 | 说明 |
|---|---|
| `POST /api/v1/lineage/save` | 解析并保存为新版本 |
| `GET /api/v1/versions` | 版本列表（分页） |
| `GET /api/v1/versions/{id}` | 版本详情与血缘图 |
| `PUT /api/v1/versions/{id}/current` | 设为当前版本 |
| `DELETE /api/v1/versions/{id}` | 删除版本 |
| `GET /api/v1/versions/diff?from=&to=` | 版本间血缘差异 |

---

## 五、P4 元数据服务接入

### 5.1 配置表

```sql
CREATE TABLE metadata_source (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NOT NULL,
    project_id     BIGINT       NOT NULL,
    name           VARCHAR(128) NOT NULL,
    type           VARCHAR(32)  NOT NULL COMMENT 'GRAVITINO / DBX',
    base_url       VARCHAR(512) NOT NULL,
    credential     VARCHAR(1024) DEFAULT NULL COMMENT '加密后的凭据，禁止明文',
    extra_config   TEXT         COMMENT 'JSON，各类型的差异化配置',
    priority       INT          NOT NULL DEFAULT 100 COMMENT '数字小的优先',
    enabled        TINYINT      NOT NULL DEFAULT 1,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_source (tenant_id, project_id, name),
    KEY idx_source_enabled (tenant_id, project_id, enabled, priority)
) COMMENT='元数据服务配置';
```

**凭据加密**：用 AES-GCM，主密钥从环境变量注入（`METADATA_SECRET_KEY`），
不落配置文件、不进代码库。响应中永不回传凭据明文，只返回是否已配置。

### 5.2 DbxMetadataProvider —— 全部基于实测（已跑通完整链路）

已用 `localhost:4224` + 一个 PostgreSQL 数据源**完整跑通** login → connect → schema 全链路。
以下均为实测结果，**多处与官方文档不符，实现时以此为准**。

#### 支持的数据源类型

> ⚠️ **本节原先的结论是错的，2026-08-22 由用户指出后更正。**
>
> 原文照着某条服务端错误信息枚举出
> `mysql, postgres, sqlite, rqlite, redis, duckdb, clickhouse, sqlserver, mongodb, dynamodb, oracle`
> 这十来项，并据此断言「**没有 hive / spark / trino，dbx 只适合 OLTP**」。
> 那只是**原生驱动的一部分**，不是 dbx 的能力边界 ——
> dbx 是通用数据库管理工具，**OLTP 与 OLAP 都覆盖**。
>
> 实际上 dbx 覆盖 **80+ 种数据库**：原生支持 MySQL / PostgreSQL / SQLite / Redis /
> MongoDB / DuckDB / ClickHouse / SQL Server / Oracle / MariaDB / TiDB / OceanBase /
> openGauss / Doris / StarRocks / Redshift / DM / KingBase / CockroachDB 等；
> **Agent 配置还可扩展到 Hive、Trino、Databricks、Snowflake、H2、DB2、Neo4j、
> Cassandra、BigQuery、Kylin 及自定义 JDBC**。
>
> 教训：不要从一条错误信息去反推对方的能力清单，更不要把这种推断写成产品文案。
> 代码里也**不再维护**「dbx 支持哪些类型」的硬编码列表，交给 dbx 自己判定。

仍然成立的实测结论：

- `db_type` 只接受 `postgres`，写 `postgresql` 会 422
- **`/api/schema/columns` 的响应字段里没有分区标记**，因此本项目经 dbx 取不到分区列。
  这一条与「支持哪些数据库」无关，是 schema 接口的形态问题；
  分区表建议用能直连 HMS 的 Gravitino 交叉验证

#### 真实响应结构

| 接口 | 实测响应 |
|---|---|
| `POST /api/auth/login` | `{"ok":true}` + `Set-Cookie: dbx_session` |
| `POST /api/connection/test` | 成功时是字符串 `"Connection successful"` |
| `POST /api/connection/connect` | 成功时返回连接 id 字符串，如 `"pgprobe"` |
| `POST /api/connection/save` | 返回 `null`（不是对象） |
| `GET /api/schema/databases` | `[{"name":"kohakuhub"}, ...]` —— **对象数组** |
| `GET /api/schema/schemas` | `["public"]` —— **字符串数组，与上一个不一致** |
| `GET /api/schema/tables` | `[{"name","table_type","comment","parent_schema","parent_name"}]` |
| `GET /api/schema/columns` | 见下 |

```json
[{
  "name": "id",
  "data_type": "integer",
  "is_nullable": false,
  "column_default": "nextval('user_id_seq'::regclass)",
  "is_primary_key": true,
  "is_unique": false,
  "extra": "serial",
  "comment": null,
  "numeric_precision": null,
  "numeric_scale": null,
  "character_maximum_length": 255
}]
```

血缘只需要 `name`（列名）与顺序；`data_type` 可选存留。

#### 与文档不符之处（照文档写会直接失败）

| 项 | 文档 | 实测 |
|---|---|---|
| `config` 字段 | 示例只给 name/db_type/host/port/username/database | **必须带 `id`**，否则 422 |
| SQLite 文件路径 | `file_path` | 实际放在 **`host`** 字段 |
| 错误响应 | 「带 `error` 字段的 JSON」 | `{version, code, messageKey, detail, operationOutcome, source, origin}` |
| databases / schemas | 未说明 | 两者**返回结构不一致**（对象数组 vs 字符串数组） |
| 连接列表的密码 | 「密码等敏感信息不会直接出现在返回 JSON 中」 | ⚠️ **实测明文返回 `"password":"hubpass"`** |

#### ⚠️ 安全要求（由上面最后一条导出）

`GET /api/connection/list` 会**明文返回数据库密码**。因此 `DbxMetadataProvider`：

- **禁止**把该接口的原始响应写入任何级别的日志
- 解析后只保留 `id` / `name` / `db_type` / `database`，**立即丢弃 password 字段**
- 我方接口对外暴露 dbx 连接列表时，必须过滤掉凭据字段
- 这一条要写成测试用例：断言日志与响应中不出现密码

#### 其它实现要求

- 会话存在 dbx 进程内存，重启即失效 → 识别 401 后自动重登录一次
- 连续 5 次登录失败锁定 60 秒 → 失败即降级，不做密集重试
- 调 schema 接口前必须先 `POST /api/connection/connect`
- **拿不到分区列**：`/api/schema/columns` 无分区概念（实测字段列表中确认），
  Hive/Spark 分区列对血缘是必需的（一期修复的 `pt` 问题），此为硬限制

### 5.3 前端配置页

新增「元数据服务」页面：类型选择、地址、凭据、优先级、启用开关、**测试连接**按钮。
血缘解析页增加「元数据来源」选择器，并显示该来源的适用范围提示。

---

## 六、P5 全局血缘图查询

持久化之后才能回答的三个问题：

| 能力 | 接口 |
|---|---|
| 血缘上溯 | `GET /api/v1/graph/upstream?field=dws.t.c&depth=3` |
| 影响分析 | `GET /api/v1/graph/downstream?field=ods.s.a&depth=3` |
| 表级视图 | `GET /api/v1/graph/table?name=dws.t` |

均默认查当前版本，可用 `versionId` 指定。深度默认 3、上限可配，防止一次拉出全图。

---

## 七、P6 转换逻辑下钻

一期发现：sqlflow 能给出算子信息，但 `SQLLineageMerger` 只保留了字段连接关系，
**转换表达式被丢弃**。这是列级血缘相对表级血缘的核心差异化价值。

目标：血缘图上能看到 `dws.t.amt = sum(ods.a.price * ods.b.rate)`。

做法：`Output.OutputColumn` 保留表达式原文 → 挂到 `lineage_edge.transform` →
前端点击边时在侧栏展示，并在 Monaco 中高亮对应 SQL 片段。

> 需要先确认 sqlflow 的 AST 中表达式原文是否可无损取出，
> 若不可行则退化为记录「聚合/表达式/直传」的类型标记。这一项存在技术不确定性。

---

## 八、P7 可观测性

Actuator + Micrometer + Prometheus；指标：解析耗时 P50/P95/P99（按方言）、
失败率、元数据来源命中率与降级次数、持久化写入耗时。

---

## 九、排期

```
第 1 周   P1 数据模型定稿 + Flyway 三方言脚本 + 租户/项目 CRUD
         （dbx schema 接口已于计划阶段实测完成，见 5.2）
第 2 周   P2 LineageRepository + 血缘写入 + H2/MySQL/PG 三库跑通同一套测试
第 3 周   P3 版本管理（新建/切换/删除/幂等）+ 接口
第 4 周   P4 metadata_source + 凭据加密 + GravitinoProvider 改造 + DbxProvider
第 5 周   P4 前端配置页 + 来源选择器
第 6 周   P5 全局图查询（上溯/影响分析/表级）+ 前端入口
第 7 周   P6 转换逻辑下钻（含可行性验证）
第 8 周   P7 可观测性 + 整体联调 + 文档
```

**测试策略**：持久化层用 Testcontainers 对 MySQL/PG 跑真实数据库测试，
H2 直接内存跑；三套后端**共用同一份测试用例**，确保行为一致。

---

## 十、本期完成的判定标准

- [ ] 同一份血缘测试在 H2 / MySQL / PG 三种后端上结果一致
- [ ] 所有查询强制带租户过滤，有用例证明跨租户拿不到数据
- [ ] 版本可新建/切换/删除，默认查最新；删除当前版本后能自动回退
- [ ] Gravitino 与 dbx 两种来源都能在页面上配置、测试连接、并被血缘解析实际使用
- [ ] 给定一个字段能查出 3 层上游与下游
- [ ] 凭据在库中为密文，接口响应中不出现明文
- [ ] CI 仍绿，且测试数守卫不被绕过

---

## 十一、已确认的四项决策

| 决策点 | 结论 | 影响 |
|---|---|---|
| 本地数据库 | **H2 file 模式** | 需 H2 2.x；三套 Flyway 方言脚本 |
| 访问层 | **JPA + JdbcTemplate 混合** | 实体用 JPA，递归 CTE 用 JdbcTemplate |
| 多租户隔离 | **共享表 + tenant_id 字段** | 必须有统一强制拦截，见 11.1 |
| P6 转换表达式 | **保留本期，先做可行性验证** | 第 7 周先验证再决定深度 |

### 11.1 多租户强制拦截的实现（关键）

选了「共享表 + tenant_id」，最大的风险是**某个查询忘了带租户条件导致越权**。
不能靠业务代码自觉，必须由基础设施强制。三层保障：

**第一层：租户上下文**

```java
public final class TenantContext {
    private static final ThreadLocal<LineageContext> HOLDER = new ThreadLocal<>();
    // 从请求头 / 认证信息解析，在拦截器中设置，请求结束务必 remove
}
```

> 注意：二期引入的 `SqlParseExecutor` 会把解析放到**独立线程**执行，
> ThreadLocal 不会自动传递。必须在提交任务时显式透传租户上下文，
> 否则解析线程里取不到租户信息。这是本设计里最容易踩的坑，实现时要专门写用例覆盖。

**第二层：JPA 侧用 Hibernate `@Filter` 自动附加条件**

```java
@FilterDef(name = "tenantFilter", parameters = {
        @ParamDef(name = "tenantId", type = Long.class),
        @ParamDef(name = "projectId", type = Long.class)})
@Filter(name = "tenantFilter",
        condition = "tenant_id = :tenantId and project_id = :projectId")
public abstract class TenantAwareEntity { ... }
```

**第三层：JdbcTemplate 侧的原生 SQL 无法自动拦截**，因此要求：
- 所有原生 SQL 统一收敛在 `LineageRepository` 的实现类里，不散落各处
- 该类的每个方法**第一个参数固定为 `LineageContext`**，从签名上强制传入
- 加一个**架构测试**（ArchUnit 或自写扫描测试）：
  断言 `lineage_` 开头的表在原生 SQL 中出现时，同一条语句必须包含 `tenant_id`；
  这样漏写条件会在 CI 阶段失败，而不是上线后变成越权漏洞

**验收**：必须有跨租户越权的负向用例 —— 用租户 A 的上下文查租户 B 的字段，
断言查不到数据，而不是只测正向路径。

### 11.2 P6 可行性验证的判定标准

第 7 周先花半天验证 sqlflow 能否取出表达式原文：

- **能无损取出** → 完整实现，`lineage_edge.transform` 存表达式原文
- **只能取到 AST 无法还原原文** → 退化为记录类型标记
  （`DIRECT` / `EXPRESSION` / `AGGREGATE`），前端展示「该字段由聚合计算得出」
- **完全取不到** → 本期移除该项，如实记录原因

无论哪种结果都要写进第三期报告，不含糊带过。
