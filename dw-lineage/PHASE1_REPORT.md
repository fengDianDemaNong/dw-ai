# 第一期（止血）完成报告

> 完成日期：2026-08-22
> 涉及仓库：`sql-tools`、`sql-tools-vue`、`sqlflow`

---

## 一、验证结论

| 套件 | 改造前 | 改造后 |
|---|---|---|
| `sqlflow` 单元测试 | **0 个执行**（被静默跳过） | **36 个全部通过** |
| `sql-tools` 单元测试 | **0 个执行**（被静默跳过） | **56 个执行，52 通过 / 4 失败（均为既有缺陷）** |
| `SQLLineageMerger` 正确性测试 | 不存在 | **新增 20 个，全部通过** |
| 血缘端到端测试 | 不存在 | **新增 11 个，全部通过** |
| 前端构建 | 通过 | 通过，且产物不再含硬编码 IP |
| 测试耗时 | Gravitino 用例网络超时占 **525 秒** | 默认排除，整包 **秒级** |

> 所有"修复前失败 → 修复后通过"的结论，都先在**未改动的代码**上跑过一遍确认缺陷真实存在，
> 再在修复后复跑；对 `sqlflow` 的改动还用 `git stash` 回退源码做了对照，确认没有引入回归。

---

## 二、原计划内的修复

### 2.1 多语句写同一目标字段时血缘被覆盖

`SQLLineageMerger.java` 原 `lineageMap.put(targetField, sourceFieldsList)` 直接覆盖。
分区表多次 `insert overwrite`、UNION 拆成多条 `insert` 时，先写入的上游全部丢失且无告警。

改为 `LinkedHashSet` 合并去重。
验证：`multipleStatementsWritingSameTargetColumn`、`twoInsertsIntoSameTableKeepBothSources`。

### 2.2 "表名"取成了 schema 名

原 `field.split("\\.", 2)[0]` 对 `schema.table.column` 取到的是 **schema**，
导致同 schema 下不同表在同层被挤成同一个 `index`，前端布局错乱、连线交叉。

抽出 `tableOf()` / `columnOf()` 统一按最后一个 `.` 切分。
验证：`tablesInSameSchemaGetDistinctIndex`、`columnsOfSameTableShareIndex`。

### 2.3 层级计算不收敛（比预估更严重）

原 BFS 用 `visited` 阻止节点重入队，被抬高层级后无法向下游传播。

**实测发现问题比方案里描述的更严重**：起始队列来自 `HashMap.keySet()`，遍历顺序不确定，
连最简单的两级链路 `stg.a → tmp.b → dws.c` 都会算错（`stg.a` 得到 1，正确值是 2）。
即同一份 SQL 两次解析可能得到**不同的图**。

改为 Kahn 拓扑排序求最长路径，入队顺序按字典序固定。
验证：`simpleChain`、`diamondDependencyUsesLongestPath`、`deepDiamondPropagatesLevels`、
`outputIsDeterministicAcrossRuns`（同输入连跑 30 次结果完全一致）。

### 2.4 无环检测

原 `findRootSources` 里那句 `// Handle cyclic dependencies` 并非处理环，
而是把已访问节点当作根节点塞进结果，静默伪造血缘。

新增 Tarjan 强连通分量检测（迭代实现，避免深链路栈溢出）：
- 环上字段写入顶层 `warnings`
- 环上的边标记 `cyclic: true`，前端可用虚线区分
- 层级计算遇环强制断开回边，不死循环

验证：`selfCycleIsDetectedAndDoesNotHang`、`mutualCycleIsDetectedAndDoesNotHang`、
`cyclicEdgesAreMarked`、`longCycleTerminates`（500 节点环）、`deepChainDoesNotOverflowStack`（1000 跳）。

### 2.5 前端生产环境永远连 localhost

`request.ts` 读 `import.meta.env.ENV`，而 Vite 只注入 `VITE_` 前缀变量，该值恒为 `undefined`，
生产构建产物永远请求 `http://localhost:8080/`；旁边还硬编码着内网 IP `10.36.218.98`。

改为相对路径 + 反向代理：
- `request.ts` 用 `VITE_API_BASE_URL`，默认走相对路径
- `vite.config.ts` 增加 dev proxy
- `nginx.conf` 增加 `/api/` 反代（后端地址由环境变量注入）
- `Dockerfile` 改用 nginx 官方 entrypoint 的模板机制
  （原先的 `ENTRYPOINT nginx` 会绕过 entrypoint，使变量替换失效）
- 超时 10s → 60s，大 SQL 不再假失败

验证：构建产物中已搜不到任何硬编码地址。

### 2.6 其它清理

- 删除 `/api/getLineageData` 这个恒返回 `data.json` 的 mock 端点
- `GlobalExceptionHandler` 不再回吐原始异常信息，改为返回 `traceId`，细节只进日志
- `LineageServiceImpl` 不再以 info 级别打印完整 SQL（敏感数据），改为 debug + 记录耗时
- 请求改用 `LineageAnalyzeRequest` DTO，加 `@NotBlank` 与 200,000 字符上限
- `MetadataServiceFactory` 删除 4 个无引用方法，**363 行 → 239 行**
- 删除 `BloodRelationConverter`（365 行，仅测试中 `println` 使用）
- 删除前端 React 全家桶（`react` / `react-dom` / `react-color` / `react-split-pane` / `antd` 等）
  及其仅有的两个引用点（`ColorPicker` 是 Header 注释标明"已不再使用"的死代码，
  且在 Vue 模板里 import React 组件本就是坏的）
- `@ant-design/icons`（React 版）换成源码实际 import 的 `@ant-design/icons-vue`
- monaco / g6 单独分包
- 补写 `sql-tools/README.md`（原先是 Gitee 默认模板）

---

## 三、计划外发现并修复的问题

这些是执行过程中实测暴露出来的，都比原计划里的条目更要命。

### 3.1 两个项目的测试套件一直在被静默跳过 ★

- **`sqlflow`**：依赖了 `testng` 7.5.1 但全部测试都是 JUnit 4。
  testng 在 classpath 上让 surefire 选用 TestNG provider，
  **21 个测试文件、36 个用例一个都没跑过**，构建却是绿的。
  → 移除未使用的 testng，surefire 锁到 3.2.5。

- **`sql-tools`**：启用 `spring-boot-starter-test` 会引入 JUnit 5，
  surefire 切到 JUnit Platform provider，而 Spring Boot 默认不含 vintage 引擎，
  既有 JUnit 4 测试同样归零。
  → 显式加 `junit-vintage-engine`。

这解释了为什么下面这些缺陷能长期存在：**没有任何测试真正跑过**。

### 3.2 目标列与 select 列表按列名匹配，而非按位置 ★★★

`sqlflow` 的 `StatementAnalyzer` 用 `Field.matchFields(..., strict=true)` 按**列名**匹配，
但 SQL 语义是**按位置**：`insert into dws.user_stat select id, name from ods.users`
应映射 `id→user_id`、`name→user_name`。

后果：**只要 ETL 过程中重命名了列（极其常见），整条语句的血缘就被丢弃**，
报 `Column 'user_id' not found in source fields: name, id`。
分区列场景同样中招（`Column 'pt' not found in source fields: ...`）。

代码里那段被注释掉的 `Streams.zip(...)` 才是正确的原始 Trino 逻辑。

→ 新增 `Field.matchFieldsByPosition()`，`visitInsert` 与 `visitCreateTableAsSelect` 均改为位置映射；
数量不匹配时多余目标列给空上游，而不是让整条语句失败。

验证：新增 `PositionalColumnMappingTest`（7 个用例）。
此修复还顺带让既有的 `HiveSqlLineageTest.testInsert` 和 5 个 `FlinkSqlLineageTest` 用例由失败转为通过。

### 3.3 带列清单的 INSERT 必定崩溃 ★★

`insertColumns` 用 `toImmutableList()` 构造，随后无条件调用 `insertColumns.addAll(staticPartitionColumns)`
（Guava 不可变集合的 `addAll` 恒抛异常）。
即**任何 `INSERT INTO t (c1, c2) SELECT ...` 都抛 `UnsupportedOperationException`**，
再被 `analyzeMultiple` 吞掉，表现为"血缘莫名其妙是空的"。

同一处还有个隐患：不带列清单时 `insertColumns = tableColumns` 直接引用元数据自身的列表，
后续往里追加分区列会**把分区列写回表元数据**，污染同一批次后续语句的分析。

→ 两个分支都改为可变的独立副本。

### 3.4 CTAS 的列别名被直接丢弃 ★

`AstBuilder.visitCreateTableAsSelect` 硬编码 `Optional.empty(),  // columnAliases 不再需要`，
而语法文件明确支持 `CREATE TABLE t (c1, c2) AS SELECT ...`，
`StatementAnalyzer` 里也有一整个分支在等这个值。

后果：目标表列名错误地沿用 select 的输出列名。
→ 按 `visitInsertInto` 既有写法正确填充。

### 3.5 单条语句失败被静默吞掉 ★★

`analyzeMultiple` 的 `catch` 只做 `System.err.println`，调用方拿不到任何信息，
无法区分"没有可解析的语句"和"语句解析失败被跳过"，
于是 3.2 / 3.3 这类缺陷统统表现为"接口 200、图是空的、用户不知道为什么"。

→ 新增 `analyzeMultipleDetailed()` 返回成功结果 + 失败明细（`MultiStatementAnalysis` / `StatementFailure`），
`analyzeMultiple` 保留原签名委托给它以兼容既有调用。
`SQLLineageMerger` 侧也把空结果/解析失败转成明确的 `warnings`，不再返回 `null`。

> 注：把 `StatementFailure` 一路透出到 HTTP 响应属于第二期"部分失败容忍"的范围，
> 本期只打通了底层能力与 `warnings` 通道。

### 3.6 编译配置

`sql-tools` 的 `maven-compiler-plugin` 硬编码 `<source>9</source><target>9</target>`，
与自身 properties 的 21、以及另两个项目的 21 都不一致，靠 kotlin 插件先编译才没炸。
→ 改为 `<release>${maven.compiler.target}</release>`。

### 3.7 依赖外部服务的测试拖垮整个测试流程

`gravitino` 包下的测试连不上服务时逐个等网络超时，**整包耗时 525 秒**。
→ 默认排除，`mvn test -Pexternal-tests` 可打开。

---

## 四、遗留的既有缺陷（未修，建议纳入第二期）

这 4 个失败在改造前就存在，只是因为测试从没跑过而没被发现。均已在 `sql-tools/README.md` 记录。

| 用例 | 现象 | 归属 |
|---|---|---|
| `TestSuperior.testMySQL` / `testMySQL2` | MySQL 方言把 `insert` 识别成 `SELECT` | superior-sql-parser |
| `TestSuperior.testPostgres` | `NoSuchMethodError: AbstractSqlParser.installCaches` | 依赖版本冲突，对应方案 2.7 的 ANTLR 版本对齐 |
| `HiveSqlLineageTest.testMultiStatement` | sqlflow 语法不支持 `set k=v` 会话设置语句 | sqlflow 语法 |

---

## 五、改动清单

**sqlflow**
- `analyzer/Field.java` — 新增 `matchFieldsByPosition()`
- `analyzer/StatementAnalyzer.java` — 位置映射；可变 insertColumns；`analyzeMultipleDetailed()` + 失败明细
- `parser/AstBuilder.java` — CTAS 列别名不再丢弃
- `pom.xml` — 移除未使用的 testng，锁定 surefire 3.2.5
- `analyzer/PositionalColumnMappingTest.java` — 新增
- `parser/presto/PartitionLineageTest.java` — 补齐元数据，修正为符合语义的断言

**sql-tools**
- `util/SQLLineageMerger.java` — 重写（合并去重 / 表名解析 / Kahn 最长路径 / Tarjan 环检测 / warnings）
- `controller/LineageController.java` — 删 mock 端点，DTO + 校验，显式 JSON content-type
- `service/LineageService.java`、`service/impl/LineageServiceImpl.java` — boolean 参数、日志脱敏与耗时
- `service/metadata/MetadataServiceFactory.java` — 删死代码，363 → 239 行
- `dto/LineageAnalyzeRequest.java`（新）、`dto/ErrorResponse.java`、`exception/GlobalExceptionHandler.java`
- `util/BloodRelationConverter.java` — 删除
- `pom.xml` — release 21、validation / actuator / test / vintage 依赖、surefire 排除外部测试
- `README.md` — 重写
- 新增 `util/SQLLineageMergerCorrectnessTest`（20）、`lineage/LineageEndToEndTest`（11）

**sql-tools-vue**
- `utils/request.ts`、`vite.config.ts`、`.env.development`、`.env.production`、`nginx.conf`、`Dockerfile`
- `package.json`、`.eslintrc.cjs` — 移除 React 依赖树
- 删除 `components/ColorPicker/`、`hooks/useIsomorphicLayoutEffect.ts`

---

## 六、如何复验

```bash
# sqlflow：36 个用例应全绿
cd sqlflow && mvn install -DskipTests && mvn test

# sql-tools：56 个用例，仅第四节列出的 4 个既有缺陷失败
cd ../sql-tools && mvn test

# 只看本期新增的两个套件，应全绿
mvn test -Dtest='SQLLineageMergerCorrectnessTest,LineageEndToEndTest'

# 前端构建，产物中不应出现硬编码地址
cd ../sql-tools-vue && npx vite build
grep -rlE "10\.36\.218\.98|localhost:8080" dist/assets/*.js   # 应无输出
```
