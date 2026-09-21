# 第二期（架构重构）细化方案 — 待确认

> 前置：第一期已完成，见 [PHASE1_REPORT.md](PHASE1_REPORT.md)
> 本期目标：**把"能跑对"变成"改得动、看得见、扛得住"**。不新增业务能力，专注结构与工程基础。
> 预估：2~4 周

---

## 总览

| # | 任务 | 价值 | 风险 | 建议 |
|---|---|---|---|---|
| T1 | `MetadataProvider` SPI 重构 | ★★★ | 中 | 必做，本期基石 |
| T2 | Gravitino 批量 + 缓存 + 降级 | ★★★ | 低 | 必做 |
| T3 | 部分失败透出到 API | ★★★ | 低 | 必做，接第一期 3.5 |
| T4 | 方言注册表化 + 能力位 | ★★ | 低 | 必做 |
| T5 | 修既有 4 个失败用例 | ★★ | 中 | 建议做 |
| T6 | 安全与稳定性基线 | ★★ | 低 | 必做 |
| T7 | OpenAPI + 统一响应 | ★★ | 中 | 建议做（破坏性） |
| T8 | 表级血缘接口 | ★★ | 低 | 建议做 |
| T9 | SQL 校验接口 | ★★ | 低 | 可选 |
| T10 | pom 清理 + CI | ★★ | 低 | 必做 |

---

## T1. `MetadataProvider` SPI 重构

**问题**：`MetadataServiceFactory` 现存两个 `getLineageStrList` 重载，主体流程完全相同，
差别只在"表结构从哪来"（SQL 内 DDL / `SqlMetadataExtractor` 推断 / Gravitino）。
新增元数据源要复制整段流程。

**做法**

```java
public interface MetadataProvider {
    String name();
    /** 批量补全表结构；查不到的表不抛异常，放进 unresolved */
    MetadataResolution resolve(Set<QualifiedObjectName> tables);
}

public record MetadataResolution(
        Map<QualifiedObjectName, SchemaTable> resolved,
        Set<QualifiedObjectName> unresolved,
        List<String> warnings) {}
```

实现类：
- `DdlMetadataProvider` —— 从 SQL 里的 `CREATE TABLE` 提取（对应现在的 `isCreateTable=true`）
- `InferredMetadataProvider` —— `SqlMetadataExtractor` 推断
- `GravitinoMetadataProvider` —— 见 T2
- `CompositeMetadataProvider` —— 按优先级串联：DDL > 外部元数据 > 推断

主流程收敛成一条：

```java
public LineageResult analyze(LineageRequest req) {
    var statements   = dialectRegistry.parse(req.dbType(), req.sql());
    var dmlSqls      = extractDml(statements);
    var flowStmts    = sqlFlowParser.createStatements(dmlSqls);
    var needed       = collectReferencedTables(flowStmts);
    var metadata     = metadataProvider.resolve(needed);   // 一次批量
    return analyzer.analyze(flowStmts, metadata);
}
```

**收益**：239 行 → 约 120 行；新增元数据源不动主流程；`unresolved` 可回传前端提示"这些表结构未知"。

**验收**：现有 11 个端到端用例全绿；新增 Provider 优先级与降级用例。

---

## T2. Gravitino 批量 + 缓存 + 降级

**问题**（现 `getLineageStrList(Catalog, ...)`）
- 在循环里逐表同步 `tableCatalog.loadTable()`，50 张表 = 50 次串行 HTTP
- 任意一张表不存在 → 异常穿透 → **整个请求失败**
- 无缓存、无超时、无重试

**做法**
- 批量入口 + 有界线程池并发（`CompletableFuture`，并发度可配，默认 8）
- Caffeine 缓存，key `metalake:catalog:schema:table`，TTL 默认 10 分钟可配，
  暴露 `POST /api/mate/cache/evict` 手动刷新
- 单表失败降级为 `unresolved` + warning，不中断整体
- 显式连接/读取超时与重试

**验收**：mock Gravitino 的单测覆盖"部分表不存在""超时""缓存命中"；
50 表场景的耗时对比数据。

---

## T3. 部分失败透出到 API（接第一期 3.5）

第一期已在 sqlflow 侧提供 `analyzeMultipleDetailed()`，但尚未接到 HTTP 响应。

**做法**：响应体补充

```json
{
  "data": { ... },
  "warnings": [ ... ],
  "failedStatements": [
    { "index": 2, "sql": "insert into ...", "error": "table xxx metadata not exists" }
  ],
  "unresolvedTables": ["ods.foo"]
}
```

前端在图上方以折叠条展示：「3 条语句中 1 条解析失败，点击查看」。

**为什么重要**：这是第一期暴露出的最大体验问题 —— 用户看到空图却不知道原因。

**验收**：一条正确 + 一条错误的混合脚本，返回 200，含正确血缘与 1 条 `failedStatements`。

---

## T4. 方言注册表化 + 能力位

**问题**
- `statements()` 里 13 分支硬编码 switch，加方言要改两处
- `DORIS` 走 `StarRocksHelper`，而独立的 `superior-doris-parser` 模块存在却未被依赖
- `fromString(databaseType.toUpperCase())` 传大写进内部用 `equalsIgnoreCase` 的方法，能跑但语义混乱
- **`/api/dbType` 返回全部 13 种方言，但 sqlflow 语法是 trino 派生的**，
  Oracle / SQLServer / ClickHouse 的列级血缘覆盖度未验证，
  用户选了却拿到空图，不知道是自己 SQL 的问题还是工具不支持

**做法**

```java
public enum SqlDialect {
    SPARK("spark", SparkSqlHelper::parseMultiStatement, Capability.COLUMN_LEVEL),
    HIVE ("hive",  SparkSqlHelper::parseMultiStatement, Capability.COLUMN_LEVEL),
    DORIS("doris", DorisSqlHelper::parseMultiStatement, Capability.COLUMN_LEVEL),  // 换专用 parser
    ORACLE("oracle", OracleSqlHelper::parseMultiStatement, Capability.TABLE_LEVEL_ONLY),
    ...
}
```

`/api/dbType` 改为返回带能力位的结构，前端按能力灰化或加标注。

**前置工作** —— ✅ 已确认：**全部 13 种方言都实测**

为每种方言各写一组用例，**用实测结果决定能力位**，不靠猜。每种方言的最小用例集：

| 用例 | 考察点 |
|---|---|
| `create table` + `insert into ... select` | 基本列级血缘、DDL 元数据提取 |
| `insert into ... select *` | 元数据展开 |
| 两表 `join` | 多来源列归属 |
| 聚合 + `group by` | 表达式列的上游集合 |
| 列重命名（目标列名 ≠ 来源列名） | 位置映射（第一期修复点，防回归） |
| 方言特有写法 | 如 hive `insert overwrite partition`、flink `insert into` |

产出一张能力位矩阵，三档：
`COLUMN_LEVEL`（列级可用）/ `TABLE_LEVEL_ONLY`（仅表级）/ `UNSUPPORTED`（解析即失败）。

> 13 种方言 × 6 类用例 ≈ 78 个用例，是本期工作量最大的一项，建议单独排一周。
> 预期会发现较多方言不支持列级血缘（sqlflow 语法是 trino 派生的），
> 这些结论本身就是有价值的产出 —— 至少能让前端不再让用户选一个注定拿不到结果的方言。

**验收**：能力位矩阵表 + 对应用例；`/api/dbType` 返回带能力位的结构。

---

## T5. 修既有 4 个失败用例

| 用例 | 排查方向 |
|---|---|
| `TestSuperior.testMySQL` / `testMySQL2` | MySQL 方言 `insert` 被识别成 `SELECT`，查 superior-sql-parser 的 MySQL 语法与 `MySqlHelper` |
| `TestSuperior.testPostgres` | `NoSuchMethodError: installCaches` —— postgres parser 与 ANTLR 运行时版本冲突，需在 `dependencyManagement` 统一（sqlflow 用 4.9.3，superior 用 4.13.1，两者同处 classpath） |
| `HiveSqlLineageTest.testMultiStatement` | sqlflow 语法不认 `set k=v`。方案：拆分阶段过滤掉 `set` 语句（成本低），或补语法（成本高） |

建议：`set` 过滤和 ANTLR 版本统一优先做（影响面大），MySQL 方言问题按实际使用需求排期。

---

## T6. 安全与稳定性基线

| 项 | 现状 | 措施 |
|---|---|---|
| 解析超时 | 无 | 独立线程池 + `orTimeout`，防深嵌套 SQL 打爆栈拖死进程 |
| 限流 | 无 | Bucket4j，按 IP/租户 |
| CORS | `allowedOrigins("*")`，方法列表漏 OPTIONS | 生产走 nginx 同源，配置白名单化 |
| 认证 | 无 | 至少 API Key；多租户则按 metalake 隔离 |
| 语句条数 | 无限制 | 上限可配（SQL 长度上限第一期已加） |

---

## T7. OpenAPI + 统一响应 —— ✅ 已确认：直接改造现有接口

现响应是自造格式（`code` / `errno` / `error` / `request_id` 混杂），
且 service 层返回**已序列化的 JSON 字符串**而非领域对象，导致无法结构化断言、无法复用。

**做法**
- service 层返回 `LineageGraph` 领域对象，序列化交给 Spring；
  `SQLLineageMerger` 由「JSON 字符串 → JSON 字符串」改为「`List<Output>` → `LineageGraph`」
- 引入 `springdoc-openapi`，提供 `/swagger-ui.html`
- 统一 `ApiResult<T>{ code, data, message, traceId }`
- **直接改造 `/api/lineage/analyze`，不保留旧格式**

**因为是破坏性变更，执行时须遵守**
1. 前后端在同一个变更内一起改，同步发布，不留过渡期
2. 先改后端 + 更新第一期的 31 个测试断言，再改前端 `services/api.ts` 与 `LineageGraph` 的数据转换
3. `graphUtil.ts` / `LineageGraph/index.vue` 直接消费 `withProcessData` / `noProcessData`，
   改契约必须同步核对这两处的字段读取
4. 完成后跑一遍前端构建 + 手工验证血缘图渲染，光有后端测试不够

> 提醒：这一项的回归风险是本期最高的，建议排在 T1/T2/T3 之后，
> 等 service 层结构稳定了再动契约，避免同时改结构和改契约。

---

## T8. 表级血缘接口

`superior-sql-parser` 的 `Statement` 已带 `inputTables` / `outputTables`，
**不需要元数据、不经过 sqlflow**，速度快一个数量级。

当前前端的"表级/字段级切换"是在列级结果上聚合的 —— 元数据缺失导致列级失败时，表级也一起没了。

新增 `POST /api/lineage/table`，作为列级失败时的**降级兜底**。

---

## T9. SQL 校验接口 —— ✅ 已确认：纳入第二期

`checkSqlSyntax(sql)` 与 `sqlKeywords()` 在 parser 里现成可用，一个都没暴露。

- `POST /api/sql/validate` → 返回 `{ valid, errors: [{ line, column, message }] }`，
  前端在 Monaco 上打红波浪线并定位
- `GET /api/sql/keywords?dbType=spark` → 关键字补全

**前端配套**：`MonacoEditor/index.vue` 接入 `monaco.editor.setModelMarkers` 做错误标注，
用 `registerCompletionItemProvider` 接关键字补全。输入防抖 500ms，避免每次击键都打后端。

**依赖关系**：`sqlKeywords()` 按方言返回，需等 T4 的方言注册表就绪后再接，否则又要写一遍 switch。

---

## T10. pom 清理 + CI

**pom 清理**（`sql-tools` 是应用不是类库，以下均为从 superior-sql-parser 抄来的残留）
- 删 `antlr4-maven-plugin`（项目下没有任何 `.g4` 文件）
- 删 `maven-source-plugin` / `maven-release-plugin` / GPG release profile
- 删 `distributionManagement`（指向内网 `172.18.1.33`）
- 修正 `<url>` / `<scm>` / `<developers>` / `<issueManagement>`（现在全指向 `melin/superior-sql-parser`）
- `dependencyManagement` 统一 ANTLR 版本（配合 T5）

**CI**（四个仓库目前只有 `sql-tools-vue` 有一个 deploy.yml）
- `build → test → 覆盖率` 流水线
- **关键：加"测试数必须大于 0"的断言** —— 第一期两个项目的测试套件都曾被静默跳过，
  这类问题必须由流水线兜住，不能再靠人工发现
- 统一 spotless / checkstyle

**补充**：后端 Dockerfile（当前只有前端有）+ 顶层 `docker-compose.yml` 一键起。

---

## 执行顺序（已按确认结果调整）

```
第 1 周   T10  pom 清理 + CI（含"测试数 > 0"断言）—— 先行，让后续改动有护栏
         T5   ANTLR 版本统一 / set 语句过滤 / MySQL 方言

第 2 周   T1   MetadataProvider SPI 重构
         T2   Gravitino 批量 + 缓存 + 降级

第 3 周   T3   部分失败透出到 API
         T4   方言注册表化 + 13 种方言能力位实测（工作量最大，可能占满整周）
         T6   安全与稳定性基线

第 4 周   T8   表级血缘接口
         T7   统一响应 + OpenAPI（破坏性，前后端同步改）—— 放最后，等结构稳定
         T9   SQL 校验 + 关键字补全（依赖 T4 的方言注册表）
```

两条排序原则：
1. **T10 必须最先**。第一期的教训是两个项目的测试套件长期被静默跳过，
   没有可信 CI 的重构等于裸奔。
2. **T7 必须最后**。它同时改后端结构和前端契约，等 T1/T2/T3 把 service 层理顺后再动，
   避免"改结构"和"改契约"两类风险叠加。

---

## 本期完成的判定标准

- [ ] CI 绿，且流水线能拦住"测试数为 0"
- [ ] `sql-tools` 既有 4 个失败用例清零（或明确记为不修并说明原因）
- [ ] `MetadataServiceFactory` 主流程收敛为一条，新增元数据源不需改主流程
- [ ] 50 表规模的 Gravitino 场景有耗时对比数据；单表缺失不再导致整体失败
- [ ] 解析部分失败时，前端能看到"哪条语句失败、为什么"
- [ ] 13 种方言的能力位矩阵产出，前端不再让用户选注定无结果的方言
- [ ] `/swagger-ui.html` 可用，前后端契约同步切换完成且血缘图渲染正常
