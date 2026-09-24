# SQL 血缘平台 优化与增强方案

> 生成日期：2026-08-22
> 覆盖仓库：`sql-tools`（后端）、`sql-tools-vue`（前端）、`sqlflow`（列级血缘引擎）、`superior-sql-parser`（多方言解析器）

---

## 0. 现状盘点

| 仓库 | 角色 | 技术栈 | 规模 |
|---|---|---|---|
| `superior-sql-parser` | 多方言 ANTLR4 解析器，语句类型 / **表级**血缘 / DDL 元数据 / 语法校验 | Kotlin + ANTLR 4.13.1 / JDK 21 | ~200 源文件，测试 42 个文件 |
| `sqlflow` | trino 派生语法，**列级**血缘语义分析 | Java + ANTLR 4.9.3 / JDK 21 | 209 main / 21 test |

> 四个项目**统一使用 JDK 21**（`sqlflow` / `superior-sql-parser` README 中残留的 "jdk11" 描述已过时，pom 中 `maven.compiler.source/target` 均为 21，需同步修正文档）。
| `sql-tools` | Spring Boot 应用，粘合上面两者 + Gravitino 元数据 | Spring Boot 3.3.11 / JDK 21 | ~1660 行 |
| `sql-tools-vue` | 前端，Monaco 编辑器 + AntV G6 血缘图 | Vue 3 + G6 v4 + Vite 4 | ~5600 行 |

数据流：
```
SQL 文本
  └─ superior-*-parser.parseMultiStatement()   拆语句、识别 CreateTable/InsertTable/CTAS/Merge
      ├─ CreateTable  → SchemaTable 喂给 MetadataService
      └─ Insert/CTAS  → sqlflow SqlFlowParser.createStatements()
                          └─ StatementAnalyzer.analyzeMultiple()  → List<String>（每条一个 Output JSON）
                              └─ SQLLineageMerger.mergeSQLLineage()  → withProcessData / noProcessData
                                  └─ 前端 G6 渲染
```

---

## 一、必须先修的正确性缺陷（P0）

### 1.1 多语句写同一目标字段时血缘被覆盖

`sql-tools/src/main/java/org/qq/util/SQLLineageMerger.java:150`

```java
lineageMap.put(targetField, sourceFieldsList);   // ← 覆盖
```

`lineageMap` 是 `Map<String, List<String>>`，key 为 `schema.table.column`。当脚本里有多条语句写同一张目标表（分区表按天多次 `insert overwrite`、UNION 被拆成多条 `insert into`、`merge` + `insert` 组合）时，**后一条语句的血缘直接覆盖前一条**，前面的上游全部丢失，且无任何告警。

**修法**：改为合并去重。

```java
lineageMap.computeIfAbsent(targetField, k -> new ArrayList<>())
          .addAll(sourceFieldsList);
// 或者 lineageMap 的 value 换成 LinkedHashSet<String> 保序去重
```

同时给合并加计数，在响应里返回 `mergedStatements` 便于排查。

---

### 1.2 "表名"取错，取成了 schema 名

`SQLLineageMerger.java:194` 和 `:381`

```java
String table = field.split("\\.", 2)[0];
```

`field` 的格式是 `schema.table.column`（见 `resources/data.json`：`dws.dws_comm_shop_linkshop_da.cal_date`）。`split(".", 2)` 的 limit=2 使结果为 `["dws", "dws_comm_shop_linkshop_da.cal_date"]`，取 `[0]` 拿到的是 **schema**，不是表。

后果：`assignTableIndexes` 把同一 level、同一 schema 下的**所有表合并成同一个 index**，前端拿到的 `index` 字段失去区分度，同层多表的排布顺序错乱、连线交叉。

**修法**：

```java
private static String tableOf(String field) {
    int i = field.lastIndexOf('.');
    return i < 0 ? field : field.substring(0, i);   // schema.table
}
```

抽成一个方法，194 和 381 两处共用。顺带把 `fieldName` 的解析统一封装成一个不可变的 `FieldRef(catalog, schema, table, column)` record，避免全代码库靠字符串 split 硬拆。

---

### 1.3 层级计算（BFS 最长路径）不收敛

`SQLLineageMerger.calculateLevels()` `:159-188`

```java
if (levelMap.get(refField) < currentLevel + 1) {
    levelMap.put(refField, currentLevel + 1);
    if (!visited.contains(refField)) {     // ← visited 阻止重入队
        queue.add(refField);
        visited.add(refField);
    }
}
```

节点被抬高层级后，因为已在 `visited` 里而**不会重新入队**，新层级无法向下游传播。菱形依赖（A→B→D，A→C→D，其中 C 路径更长）会让 D 的 level 偏小，前端出现同层连线甚至反向边。

**修法**（二选一）：
- **推荐**：对血缘 DAG 做 Kahn 拓扑排序，按拓扑序松弛，一次遍历得到正确的最长路径层级，顺带天然检出环。
- 简单版：去掉 `visited`，改为无条件重入队 + 迭代次数上限（`|V| * |E|`）兜底防环。

---

### 1.4 无环检测

`insert into t select ... from t`、或多语句互相引用，都会产生环。当前：
- `calculateLevels` 在去掉 `visited` 后会死循环；保留 `visited` 则静默产出错误层级。
- `findRootSources` `:341-363` 把"已访问过的节点"当作 root 塞进结果（`roots.add(source); // Handle cyclic dependencies`），这不是处理环，是伪造出一个不存在的根节点。

**修法**：在 `mergeSQLLineage` 入口跑一次 Tarjan/DFS 环检测，检出后：
1. 把环上的边标记 `"cyclic": true` 一并返回，前端用虚线渲染；
2. 层级计算时跳过回边；
3. 响应里加 `warnings: [...]` 字段告知用户。

---

### 1.5 前端生产环境 baseURL 永远指向 localhost

`sql-tools-vue/src/utils/request.ts:4`

```ts
if (import.meta.env.ENV == 'prd') {          // ← 永远 undefined
  baseURL = 'http://10.36.218.98:8080/';     // ← 内网 IP 硬编码进源码
} else {
  baseURL = 'http://localhost:8080/';        // ← 生产实际走这里
}
```

Vite 只把 `VITE_` 前缀的变量注入 `import.meta.env`（外加 `MODE`/`DEV`/`PROD`/`BASE_URL`/`SSR`）。`.env.production` 里定义的是 `VITE_ENV`，所以 `import.meta.env.ENV` 恒为 `undefined`，生产构建产物永远请求 `http://localhost:8080/`。

**修法**：
```ts
const baseURL = import.meta.env.VITE_API_BASE_URL ?? '/api-backend/';
```
把地址移到 `.env.*` 里，生产默认走**相对路径 + nginx 反代**（`nginx.conf` 里加一段 `location /api/ { proxy_pass http://backend:8080; }`），彻底消灭硬编码 IP，也顺带解决跨域。

---

### 1.6 其他 P0 级小问题

| 位置 | 问题 |
|---|---|
| `LineageController.java:43-52` | `/api/getLineageData` 是残留 mock 端点，无条件返回 classpath 里的 `data.json`。删除。 |
| `SQLLineageMerger.java` 5 处 | `e.printStackTrace(); return null;` → 上游拿到 null 再 NPE。改为抛受检的业务异常。 |
| `SQLLineageMerger.mergeSQLLineage(list, col):30-52` | 先算了一遍 `relevantLineage`，然后 `filterLineageData` 里把同样的判断又做了一遍，`relevantLineage` 只用来判空。删掉重复遍历。 |
| `SQLLineageMerger.mergeSQLLineage:16-18` | 空输入返回 `null` → `ResponseEntity.ok(null)`，前端拿到空 body。应返回空结构 + 明确提示"未识别到 insert/CTAS 语句"。 |
| 列过滤 `endsWith("." + columnName)` | 跨所有表匹配同名列，无法指定"只看 `dws.t1.id`"。应支持全限定名过滤。 |
| `GlobalExceptionHandler:16-18` | 兜底把原始异常 message 回吐给前端（栈信息/内网地址泄露）。应返回 traceId，细节只进日志。 |

---

## 二、架构与工程债（P1）

### 2.1 `MetadataServiceFactory` 四份重复实现

`MetadataServiceFactory.java` 共 363 行，其中 `getLineageStrListFromCreate`、`getLineageStrList(String,String)`、`getLineageStrList(String,String,String)`、`getLineageStrList(Catalog,String)` 四个方法 **90% 代码相同**，差别只在"表结构从哪来"：

- 从 SQL 里的 `CreateTable` DDL 拿
- 从 `SqlMetadataExtractor` 推断
- 从 Gravitino `tableCatalog.loadTable()` 拿

且经 grep 确认，`getLineageStrListFromCreate`、`analyzeMultipleSsql`、`analyzeOneSsql`、两参版 `getLineageStrList` **在生产路径上全部无引用**（死代码）。

**重构方案**：

```java
public interface MetadataProvider {
    /** 批量补全表结构；找不到的表返回空，不抛异常 */
    Map<QualifiedObjectName, SchemaTable> resolve(Set<QualifiedObjectName> tables);
}
```

实现类：`DdlMetadataProvider`（从 SQL DDL）、`InferredMetadataProvider`（SqlMetadataExtractor 推断）、`GravitinoMetadataProvider`、`HiveMetastoreMetadataProvider`、`JdbcMetadataProvider`。

用 `CompositeMetadataProvider` 按优先级串联（DDL > 外部元数据 > 推断），单一 pipeline：

```java
public LineageResult analyze(LineageRequest req) {
    var statements   = dialectRouter.parse(req.dbType(), req.sql());   // superior-sql-parser
    var dmlSqls      = extractDml(statements);
    var flowStmts    = sqlFlowParser.createStatements(dmlSqls);
    var neededTables = collectReferencedTables(flowStmts);
    var metadata     = metadataProvider.resolve(neededTables);          // 一次批量解析
    return analyzer.analyze(flowStmts, metadata);
}
```

预计 363 行 → 约 120 行，且新增元数据源不用改主流程。

---

### 2.2 Gravitino 调用无缓存、无批量、无兜底

`MetadataServiceFactory.java:251, :293`

```java
Table tableOb = tableCatalog.loadTable(NameIdentifier.of(schema, tableName));
```

在循环里逐表同步调用远程 HTTP。一条涉及 50 张表的 SQL = 50 次串行往返；任意一张表在 Gravitino 中不存在，直接抛异常导致**整个请求失败**，而不是降级到"该表结构未知"。

**修法**：
- `MetadataProvider.resolve()` 改为批量入口，内部可并发（`CompletableFuture` + 有界线程池）。
- 加 Caffeine 缓存：key = `metalake:catalog:schema:table`，TTL 可配（默认 10 min），支持手动刷新端点。
- 单表解析失败 → 记 warning，继续；最终结果里 `unresolvedTables: [...]` 返回给前端提示。
- Gravitino client 配置连接/读取超时与重试。

---

### 2.3 方言路由的问题

`MetadataServiceFactory.statements()` `:54-88`：

- `DORIS` 走 `StarRocksHelper`，但 `superior-sql-parser` 里**存在独立的 `superior-doris-parser` 模块**，而 `sql-tools/pom.xml` 根本没依赖它。Doris 的 `SET`/`UNSET` 等语句支持（见 superior 仓库提交 `f23bc656`）用不上。
- `fromString(databaseType.toUpperCase().trim())` 传大写进一个内部用 `equalsIgnoreCase` 的方法，语义混乱（能跑，但是巧合）。
- `switch` 硬编码 13 个分支，加方言要改枚举 + 改 switch 两处。

**修法**：把方言注册表化。

```java
public enum SqlDialect {
    SPARK("spark", SparkSqlHelper::parseMultiStatement, true),
    HIVE ("hive",  SparkSqlHelper::parseMultiStatement, true),
    DORIS("doris", DorisSqlHelper::parseMultiStatement, true),   // 换成专用 parser
    MYSQL("mysql", MySqlHelper::parseMultiStatement,    false),  // 第三个参数：是否支持列级血缘
    ...;
}
```

第三个参数很重要：**当前 `/api/dbType` 返回全部 13 种方言，但 sqlflow 的语法是 trino 派生的**，对 Oracle / SQL Server / ClickHouse 这类方言的列级血缘覆盖度存疑。应该区分"支持表级血缘"和"支持列级血缘"两个能力位，前端按能力灰化选项，避免用户选了 Oracle 拿到一个空图还不知道为什么。

---

### 2.4 没有持久化 —— 最大的产品能力缺口

`sql-tools/bin/ini.sql` 已经设计好了 `tbls` / `columns_v2` / `col_rel` 三张血缘存储表，但：
- `pom.xml` 里 `mybatis-spring-boot-starter` 依赖**被注释掉**；
- 代码里**零处**引用这些表。

即当前产品是一个**一次性即席解析器**：贴一段 SQL，看一张图，关掉就没了。无法回答数据平台最核心的三个问题：
1. 这张表 / 这个字段的上游是谁（跨作业、跨 SQL）？
2. 我改这个字段，会影响下游哪些报表？
3. 上周和这周血缘有什么变化？

这是**最高价值的增强方向**，详见第三章 A。

---

### 2.5 API 契约弱

```java
@PostMapping("/lineage/analyze")
public ResponseEntity<Object> analyzeSqlLineage(@RequestBody Map<String, String> requestParams) {
    String isCreateTable = requestParams.get("isCreateTable");   // String 判 "true"
    ...
    String result = lineageService.analyzeSqlLineage(...);       // 返回已序列化的 JSON 字符串
    return ResponseEntity.ok().body(result);
}
```

问题：无 DTO、无 `@Valid` 校验、无 OpenAPI 文档、布尔值用字符串、service 层返回 `String` 而不是对象（导致 Jackson 二次处理、无法单测断言结构、无法复用）。

**修法**：
- 定义 `LineageAnalyzeRequest` / `LineageResponse` record，加 `@NotBlank`、`@Size(max = 200_000)`（防超大 SQL 打爆内存）。
- Service 层返回**领域对象** `LineageGraph`，序列化交给 Spring。`SQLLineageMerger` 从"JSON 字符串 → JSON 字符串"改成"`List<Output>` → `LineageGraph`"，可测性大幅提升。
- 引入 `springdoc-openapi`，`/swagger-ui.html`。
- 统一响应包装 `ApiResult<T>{ code, data, message, traceId }`，替代现在 `code/errno/error/request_id` 混杂的自造格式。

---

### 2.6 安全与稳定性

| 项 | 现状 | 建议 |
|---|---|---|
| CORS | `allowedOrigins("*")`，方法列表漏 `OPTIONS`/`PATCH` | 生产走 nginx 同源，配置白名单化 |
| 认证鉴权 | 无 | 至少加 API Key / JWT，多租户场景加 metalake 级隔离 |
| 限流 | 无 | Bucket4j 或网关层，按 IP/租户 |
| 输入大小 | 无限制 | SQL 长度上限 + 语句条数上限 |
| ANTLR 深嵌套 | 无防护 | 解析超时（`CompletableFuture.orTimeout`）+ 独立线程池隔离，避免深嵌套 SQL 打爆栈拖死整个进程 |
| 错误信息 | 原始异常 message 回吐 | traceId + 脱敏 |

---

### 2.7 构建配置

`sql-tools/pom.xml`：
- `maven-compiler-plugin` 硬编码 `<source>9</source><target>9</target>`，而同一 pom 的 `properties` 里是 21，且 `sqlflow` / `superior-sql-parser` 也都是 21 —— 三个项目里只有 `sql-tools` 的 compiler plugin 掉队，靠 kotlin plugin 先编译才没炸，是定时炸弹。应改为 `<release>21</release>`。
- 配了 `antlr4-maven-plugin`，但 `sql-tools` 下**没有任何 `.g4` 文件**，纯冗余。
- `maven-source-plugin` / `maven-release-plugin` / GPG release profile / `distributionManagement`（内网 `172.18.1.33`）—— 这些是从 `superior-sql-parser` 抄来的，`sql-tools` 是应用不是类库，应全部删除。
- `<url>` / `<scm>` / `<developers>` / `<issueManagement>` 全部指向 `melin/superior-sql-parser`，与本仓库无关。
- 依赖了 12 个 `superior-*-parser`，全部打进 jar，但其中若干方言的列级血缘并不可用（见 2.3）。

`sqlflow` 与 `superior-sql-parser` 的 **ANTLR 版本不一致**（4.9.3 vs 4.13.1），同时进 classpath 存在运行时冲突风险，需显式验证并在 `dependencyManagement` 里锁定。

---

### 2.8 测试与 CI

- `sql-tools`：`spring-boot-starter-test` 依赖**被注释掉**，无任何 Spring 集成测试。现有 test 目录是一堆 `System.out.println` 式的手工验证脚本（`HiveSqlLineageTest` 里大量 println 而非断言）。
- `sqlflow`：209 main / 21 test。
- CI：只有 `sql-tools-vue/.github/workflows/deploy.yml`，另外三个仓库**无 CI**。
- 无覆盖率、无 lint 门禁（`superior-sql-parser` 有 spotless，另外三个没有）。

---

### 2.9 前端

**依赖**
- `package.json` 的 `dependencies` 里同时装着 **React 全家桶**（`react` / `react-dom` / `react-color` / `react-split-pane` / `antd`）和 Vue 全家桶（`vue` / `ant-design-vue`），`devDependencies` 里还有 `@vitejs/plugin-react`、`eslint-plugin-react`。这是从 React 版本迁移过来的残留，全是死代码，白白拖慢安装与构建。**直接删除**。
- `@antv/g6` 是 **v4.8.17**，v4 已停止特性更新。需评估迁移 v5（图形 API 大改，`registerShape.ts` 439 行 + `CustomDagreLayout.ts` 277 行需要重写）。
- `monaco-editor` 全量引入，未做 worker 分包，首屏 bundle 很大。
- `typescript` 4.9 / `vite` 4 / `eslint` 8 / `prettier` 2 全线落后。
- `axios timeout: 10000` —— 大 SQL 解析很容易超过 10s 被前端掐断（后端还在跑），需要提到 60s 并配合后端异步化。

**代码结构**
- `LineageGraph/index.vue` 852 行、`pages/index.vue` 784 行、`Header/index.vue` 625 行。视图与逻辑混杂，`Header` 通过 **14 个 props + 10 个 emit** 与父组件通信。
- 无 Pinia / 无状态管理层，无前端单测。
- `src/test/` 下的 mock 数据（`data.json` / `sql.ts`）被 `pages/index.vue` 直接 import 进生产包。

---

### 2.10 文档

- **`sql-tools`（最核心的应用）的 README 仍是 Gitee 默认模板**，一个字没写。
- workspace 根目录没有总览 README，新人看不出四个仓库如何配合、如何本地起。
- `sqlflow` 和 `superior-sql-parser` 的 README 质量不错，可作为范本。

---

## 三、功能增强建议（按价值排序）

### A. 血缘持久化 + 全局血缘图 ★★★★★

把 `bin/ini.sql` 落地，解析结果入库，从"即席解析器"升级为"血缘平台"。

**新增能力**
| 能力 | 接口 | 说明 |
|---|---|---|
| 血缘上溯 | `GET /api/graph/upstream?field=dws.t.c&depth=3` | 跨 SQL、跨作业追溯 |
| 影响分析 | `GET /api/graph/downstream?field=...&depth=N` | 改字段前评估影响面 |
| 表级视图 | `GET /api/graph/table?name=dws.t` | 表粒度聚合 |
| 血缘 diff | `GET /api/graph/diff?job=x&from=v1&to=v2` | SQL 变更导致的血缘变化 |
| 孤儿检测 | `GET /api/graph/orphans` | 无下游的表 = 潜在可下线 |

**存储选型**
- **方案 1（推荐起步）**：MySQL + 递归 CTE（8.0+）。复用已有 `ini.sql` 表结构，加 `job_id` / `version` / `sql_hash` 三列。3~4 层遍历性能足够。
- **方案 2（规模化）**：Neo4j / NebulaGraph。深度遍历、路径查询天然快，但引入新组件。

建议先做方案 1，把 DAO 层抽象成 `LineageRepository` 接口，规模上来后换实现。

**入库时机**：解析成功后异步写（避免拖慢同步接口），带 `sql_hash` 幂等去重。

---

### B. 批量 / 异步解析 + 结果缓存 ★★★★☆

现在只有单次同步接口，一个大脚本卡住整个请求。

- `POST /api/lineage/jobs` 提交 SQL 批次 → 返回 `jobId`
- `GET  /api/lineage/jobs/{jobId}` 查状态与结果
- **部分失败容忍**：单条语句解析失败不中断其他语句，结果里带 `failedStatements: [{ index, sql, error }]`（当前是一条失败全盘失败）
- 结果缓存：key = `sha256(sql) + dbType + metadataVersion`，Caffeine 本地 + Redis 可选

配合前端 SSE / 轮询显示进度。

---

### C. 转换逻辑（算子）下钻 ★★★★☆

`sqlflow` README 明确说支持"算子信息"，但当前 `SQLLineageMerger` 输出的 JSON **只有 `fieldName` 的连接关系，转换表达式全部丢弃**。

血缘图上应该能看到「`dws.t.amt = sum(ods.a.price * ods.b.rate)`」这样的加工逻辑 —— 这是列级血缘工具相对表级工具的**核心差异化价值**，现在白白丢掉了。

**做法**：`Output.OutputColumn` 保留表达式原文 → merge 时挂到边上 → 前端点击边/节点弹出侧边栏展示 SQL 片段并在 Monaco 里高亮对应位置。

---

### D. 表级血缘独立接口 ★★★☆☆

`superior-sql-parser` 的 `Statement` 已带 `inputTables` / `outputTables`，**不需要元数据**、不需要 sqlflow、速度快一个数量级。当前前端的"表级/字段级切换"是在列级结果上聚合出来的，元数据缺失时表级血缘也跟着失败。

新增 `POST /api/lineage/table`，纯 superior-sql-parser 路径。作为列级血缘失败时的**降级兜底**。

---

### E. SQL 校验与编辑器增强 ★★★☆☆

`superior-sql-parser` 的 `checkSqlSyntax(sql)` 和 `sqlKeywords()` 现成可用，但一个都没暴露。

- `POST /api/sql/validate` → Monaco 实时红波浪线 + 错误定位（行列号）
- `GET /api/sql/keywords?dbType=spark` → 关键字补全
- 结合元数据的表名 / 字段名智能补全（`/api/mate/tables` 已有雏形）
- 方言感知的格式化（当前 `sql-formatter` 是通用的）

---

### F. 元数据源可插拔 ★★★☆☆

当前硬绑 Gravitino。按 2.1 的 `MetadataProvider` SPI 补齐：Hive Metastore（Thrift）、JDBC `information_schema`、DataHub、静态 JSON 上传。

顺带把 `JdbcQueryMetadataService` 补全 —— 它现在是个空壳（`getView()` 恒返回 empty 导致**视图无法穿透**，`isAggregationFunction()` 恒 `false` 导致聚合函数识别失效），且只在测试里被引用。

---

### G. 可观测性 ★★★☆☆

- Actuator + Micrometer + Prometheus
- 关键指标：解析耗时 P50/P95/P99（按方言分维度）、失败率、元数据缓存命中率、未解析表数
- 结构化日志（JSON）+ traceId 贯穿
- 当前 `LineageServiceImpl` 在 `logger.info` 里打印**完整 SQL**（`:55`），生产环境有敏感数据泄露风险，改为按需 debug 级别 + 截断

---

### H. 前端体验 ★★☆☆☆

- 删除 React 依赖，升级 Vite 5 / TS 5 / ESLint 9
- Pinia 收拢状态，拆分 852 行的 `LineageGraph`
- G6 v4 → v5 迁移评估（工作量较大，可延后）
- 血缘图导出：PNG（已有）+ SVG + JSON + CSV
- **URL 分享**：SQL 压缩进 query 或存后端换短链，方便团队协作贴链接
- 节点搜索定位、路径追踪高亮（部分已有，可强化）
- 大图性能：节点 > 500 时启用虚拟化 / 按 level 懒加载

---

### I. 工程化 ★★★☆☆

- 顶层 `docker-compose.yml`：backend + frontend + MySQL + （可选）Gravitino，一键起
- **后端 Dockerfile**（当前只有前端有）
- 四个仓库统一加 CI：`build → test → 覆盖率 → 镜像推送`
- `spotless` / `checkstyle` 统一（superior 已有，另外三个补上）
- 顶层 README + `sql-tools` README 重写

---

## 四、实施路线

### 第一期：止血（1~2 周）

目标：**结果正确、生产可用**。改动集中在 `SQLLineageMerger` + `request.ts`，风险低。

1. 修 1.1 血缘覆盖（`put` → `merge`）
2. 修 1.2 表名解析（抽 `FieldRef`）
3. 修 1.3 层级计算（Kahn 拓扑排序）
4. 加 1.4 环检测 + warning 返回
5. 修 1.5 前端 baseURL + nginx 反代 + 删硬编码 IP
6. 清理 1.6 各小问题、删除 mock 端点与死代码
7. **补齐 `SQLLineageMerger` 单元测试**（这是重构的安全网，必须先于第二期）：覆盖菱形依赖、多语句同目标表、环、`select *` 展开、分区字段

> 交付判据：一组覆盖上述场景的黄金用例，输出 JSON 与人工标注一致。

### 第二期：架构重构（2~4 周）

8. 引入 DTO + 校验 + OpenAPI（2.5）
9. `MetadataProvider` SPI 重构，消除四份重复（2.1）
10. Gravitino 批量 + 缓存 + 降级（2.2）
11. 方言注册表化 + 能力位（2.3），接入 `superior-doris-parser`
12. pom 清理、ANTLR 版本对齐（2.7）
13. Spring Boot 集成测试 + CI（2.8）
14. 表级血缘接口（D）+ SQL 校验接口（E）
15. 安全基线：限流、大小限制、解析超时、错误脱敏（2.6）

### 第三期：能力升级（4~8 周）

16. 血缘持久化 + 全局图查询（A）—— 本期核心
17. 批量异步 + 缓存（B）
18. 转换逻辑下钻（C）
19. 可观测性（G）
20. 前端重构（H）+ docker-compose（I）

---

## 五、快速收益清单

不需要架构改动、半天内可完成、立刻见效的：

| # | 改动 | 文件 | 收益 |
|---|---|---|---|
| 1 | `lineageMap.put` → `computeIfAbsent().addAll()` | `SQLLineageMerger.java:150` | 修复多语句血缘丢失 |
| 2 | `split("\\.", 2)[0]` → `lastIndexOf('.')` | `SQLLineageMerger.java:194,381` | 修复布局错乱 |
| 3 | `import.meta.env.ENV` → `VITE_ENV` | `request.ts:4` | 生产环境能连上后端 |
| 4 | 删除 React 全家桶依赖 | `package.json` | 构建提速、包体减小 |
| 5 | 删除 `/api/getLineageData` mock 端点 | `LineageController.java:43` | 消除误导 |
| 6 | `axios timeout` 10s → 60s | `request.ts:12` | 大 SQL 不再假失败 |
| 7 | 关闭 SQL 全文 info 日志 | `LineageServiceImpl.java:55` | 防敏感数据泄露 |
| 8 | 删除死代码（4 个未引用方法 + `BloodRelationConverter`） | `MetadataServiceFactory.java` 等 | 减少 400+ 行维护面 |
| 9 | 写 `sql-tools/README.md` | — | 当前还是 Gitee 模板 |

---

## 附：确认为死代码的部分

grep 全量确认，以下在生产路径无任何引用：

- `MetadataServiceFactory.getLineageStrListFromCreate()` `:91-133`
- `MetadataServiceFactory.getLineageStrList(String, String)` `:135-174`（两参版）
- `MetadataServiceFactory.analyzeMultipleSsql()` `:329-345`
- `MetadataServiceFactory.analyzeOneSsql()` `:347-361`
- `util/BloodRelationConverter.java`（365 行，仅测试中 `println` 使用）
- `service/metadata/JdbcQueryMetadataService.java`（仅测试使用，且为未实现的空壳）
- `sql-tools/pom.xml` 中的 `antlr4-maven-plugin`（无 `.g4` 文件）
