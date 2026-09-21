# 第二期（架构重构）完成报告

> 完成日期：2026-08-22
> 前置：[PHASE1_REPORT.md](PHASE1_REPORT.md)
> 计划：[PHASE2_PLAN.md](PHASE2_PLAN.md)

---

## 一、验证结论

| 项目 | 一期结束时 | 二期结束时 |
|---|---|---|
| `sqlflow` | 36 通过 | **36 通过** |
| `sql-tools` | 56 执行 / 52 通过 / **4 个既有缺陷** | **91 通过 / 0 失败** |
| `superior-sql-parser` | 未纳入 | 构建通过，**跨模块类冲突 32 → 0** |
| 前端构建 | 通过 | 通过，产物无硬编码地址 |

除单元测试外，**启动真实服务做了端到端验证**（见第四节），不是只跑测试。

---

## 二、10 项任务全部完成

| # | 任务 | 关键产出 |
|---|---|---|
| T1 | MetadataProvider SPI 重构 | 四份重复流程收敛为一份；新增元数据源不必改主流程 |
| T2 | Gravitino 批量 + 降级 | 并发加载、单表缺失不再导致整体失败（缓存按决定不做） |
| T3 | 部分失败透出 API | `failedStatements` / `unresolvedTables` 直达前端 |
| T4 | 方言注册表化 + 能力位 | 13 种方言**实测**能力矩阵，`/api/dialects` 暴露 |
| T5 | 修复既有 4 个缺陷 | 全部清零 |
| T6 | 安全与稳定性基线 | 解析超时、栈溢出隔离、CORS 白名单、并发上限 |
| T7 | 统一响应 + OpenAPI | service 返回领域对象；`/swagger-ui.html` 可用 |
| T8 | 表级血缘接口 | `POST /api/lineage/table`，不需元数据 |
| T9 | SQL 校验 + 关键字 | `POST /api/sql/validate`、`GET /api/sql/keywords` |
| T10 | pom 清理 + CI + 容器化 | CI 含「测试数 > 0」守卫；补齐后端 Dockerfile 与 compose |

---

## 三、过程中发现并修复的额外缺陷

### 3.1 superior-sql-parser 有 32 个跨模块重名类 ★★★

同一个全限定类名出现在两个 jar 里，签名却不同。谁先被 classpath 加载谁生效，
另一个必然 `NoSuchMethodError`。

| 冲突 | 影响 |
|---|---|
| redshift 的 31 个类落在 `io.github.melin.superior.parser.postgre` 包 | **postgres 与 redshift 不能共存** |
| trino 的 `AbstractSqlParser` 落在 `io.github.melin.superior.parser.spark` 包 | **trino 与 spark 不能共存** |

这解释了一期遗留的 `TestSuperior.testPostgres` 报 `NoSuchMethodError`。
两处都是复制模块时忘了改 package（ANTLR 生成的类反而在正确的包下）。
已把手写代码归位到各自的包，**冲突降为 0**，并用脚本比对 jar 内容确认。

### 3.2 `spring-boot-maven-plugin` 从未配置 ★★

`mvn package` 产出的 jar 不含依赖也没有启动类，**根本无法运行**。
一期没发现是因为一直用 `mvn test`，没真正启动过服务。已补上并验证 `java -jar` 可直接启动。

### 3.3 SQL 校验的行号解析 bug（本期自引入并当场修复）

superior 的报错格式是 `(line 3, pos 0)`，而我最初只匹配 ANTLR 原生的 `line 3:0`，
导致**所有语法错误的行号都退化成 1**，编辑器无法定位。已改为兼容两种格式并加回归测试。

### 3.4 一期遗留的「MySQL 方言缺陷」实为测试写错

实测 `MySqlHelper` 对 `insert into ... select` 返回 `InsertTable`、类型 `INSERT`，**完全正确**。
原用例把它当 `QueryStmt` 判断因而直接 `fail()`，且对同一个值先断言 `INSERT` 又断言 `SELECT`，
自相矛盾。已按真实语义改正 —— **解析器没有问题，是用例的问题**。

### 3.5 ClickHouse 关键字未实现

`ClickHouseHelper.sqlKeywords()` 只返回一个占位符 `[_]`，其余 12 种方言正常（206~2247 个）。
属上游缺口，已用测试锁定现状：一旦上游补齐，测试会失败并提醒纳入通用用例。

---

## 四、方言能力矩阵（T4 实测结论）

**这不是推测，是逐条用例跑出来的。**

| 能力 | 结论 |
|---|---|
| **列级血缘分析** | 13 种方言在拿到表结构后**全部可用** |
| **从 SQL 内 DDL 提取表结构** | **trino / presto / sqlserver 不支持** |

那三种方言的 `CREATE TABLE` 会被解析成 `DefaultStatement`，拿不到列，
因此**必须依赖外部元数据服务**；仅靠 SQL 内建表语句时 `select *`、聚合、列重命名会缺列。

已通过 `GET /api/dialects` 暴露该能力位并附提示文案，前端可据此提醒用户。
这一结论正好支撑「元数据从外部服务获取」的方向。

---

## 五、端到端验证记录

启动真实服务（`java -jar`）后逐项验证：

| 验证项 | 结果 |
|---|---|
| `/actuator/health` | `{"status":"UP"}` |
| 列重命名血缘（一期修复点） | `dws.stat.user_id ← ods.users.id` 正确 |
| 部分失败容忍 | 正常语句血缘照常返回；失败语句带 `index=2` 与原因 |
| 表级血缘（trino） | 正确返回 2 条边，**验证了对无 DDL 能力方言的降级价值** |
| SQL 校验多行定位 | `line=3 column=1`，定位准确 |
| 参数校验（空 SQL） | HTTP 400 + 明确提示 |
| 错误响应 | 含 `traceId`，堆栈不外泄 |
| Swagger UI | HTTP 200，10 个接口全部收录 |
| Gravitino 连通（用你提供的 localhost:8090） | 正常，返回空 metalake 列表 |

---

## 六、其它改进

- **配置外部化**：三个 profile 里的内网 IP `192.168.110.21` 全部清除，
  改为 `${GRAVITINO_URL:...}`，可用环境变量注入
- **CI 守卫**：一期出现过「整个测试套件被静默跳过、构建却是绿的」，
  流水线加入测试数下限检查，并做了正反两向验证（真实 91 通过 / 模拟 0 被拦截）
- **前端诊断展示**：血缘图上方新增可折叠提示条，展示失败语句、结构未知的表与告警
- **容器化**：补齐后端 Dockerfile，新增顶层 `docker-compose.yml`（语法已校验）

---

## 七、遗留与后续

以下**已确认不在本期范围**：

| 项 | 决定 |
|---|---|
| Gravitino 元数据缓存 | 你确认暂不做，只做降级 + 批量并发 |
| `JdbcMetadataProvider` | 你确认本期只抽 SPI，不实现 |
| dbx 元数据接入 | 移入第三期（含配置页面） |

已知上游缺口（非本项目可控，已记录在 `sql-tools/README.md`）：

- trino / presto / sqlserver 的 `CREATE TABLE` 无法提取表结构
- ClickHouse 的 `sqlKeywords()` 未实现

---

## 八、如何复验

```bash
# 依赖（SNAPSHOT，需先安装）
(cd sqlflow             && mvn install -DskipTests && mvn test)   # 36 通过
(cd superior-sql-parser && mvn install -DskipTests)

# 后端：91 个用例应全绿
(cd sql-tools && mvn clean test)

# 启动并验证接口
(cd sql-tools && mvn package -DskipTests && java -jar target/sql-tools-1.0-SNAPSHOT.jar)
curl http://localhost:8080/api/dialects
open http://localhost:8080/swagger-ui.html

# 前端
(cd sql-tools-vue && npx vite build)
```
