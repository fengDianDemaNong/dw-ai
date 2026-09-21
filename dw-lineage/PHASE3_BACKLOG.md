# 第三期待办（持续收集，第二期结束后再细化成正式计划）

---

## A. 元数据服务接入页面（用户 2026-08-22 提出）

**需求**：元数据统一从外部服务获取，暂时支持两种——**Gravitino** 与 **dbx**。
增加一个页面用于接入元数据服务，在里面配置这两种服务的地址。

### 后端

基于第二期 T1 的 `MetadataProvider` SPI 新增 `DbxMetadataProvider`，与已有的
`GravitinoMetadataProvider` 并列，由用户在页面上选择启用哪一个。

需要新增元数据服务配置的持久化（当前 Gravitino 地址是写死在 `application-*.yml` 里的）：

| 字段 | 说明 |
|---|---|
| `id` / `name` | 配置标识 |
| `type` | `GRAVITINO` / `DBX` |
| `baseUrl` | 服务地址 |
| `credential` | dbx 需要密码；**必须加密存储**，不能明文落库 |
| `enabled` | 是否启用 |
| `priority` | 多个服务时的优先级，供 `CompositeMetadataProvider` 使用 |

配套接口：CRUD + **连接测试**（页面上要有"测试连接"按钮）。

### dbx 对接要点（已通读其 Web API 文档）

可用接口：

| 接口 | 用途 |
|---|---|
| `POST /api/auth/login` `{password}` | 登录，返回 `Set-Cookie: dbx_session=...` |
| `GET /api/auth/check` | 查认证状态 |
| `GET /api/connection/list` | 已保存的连接列表 |
| `POST /api/connection/connect` | **建立连接（调 schema 接口前必须先做）** |
| `GET /api/schema/databases` | 库列表 |
| `GET /api/schema/schemas` | schema 列表 |
| `GET /api/schema/tables?connection_id=&database=&schema=` | 表列表 |
| `GET /api/schema/columns?connection_id=&database=&schema=&table=` | **字段结构，血缘所需的核心接口** |

默认端口 `4224`，反向代理下需配 `DBX_PUBLIC_BASE_PATH`。

### ⚠️ 对接风险（决策前需评估）

1. **官方声明这不是稳定契约**
   文档首段明确写：「这套 API 主要服务于 DBX Web 界面……不是单独对外承诺的集成契约，
   路由和字段可能随版本调整」，并推荐用 `@dbx-app/cli` 或 `@dbx-app/mcp-server` 集成。
   → 直连 `/api/schema/*` 属于依赖内部接口，dbx 升级有断裂风险。
   → **替代方案**：走 dbx 的 MCP server 或 CLI，稳定性更好但集成方式不同（需评估）。

2. **有状态交互**
   schema 接口要求目标连接处于已连接状态，需先 `POST /api/connection/connect`。
   会话保存在 dbx Web 进程内存中，**进程重启即失效** → Provider 必须能识别 401 并自动重登录。

3. **登录锁定**
   连续 5 次登录失败锁约 60 秒 → 重试策略要克制，不能无脑重试。

4. **拿不到分区列** ★
   dbx 面向 MySQL / PostgreSQL / Redis / MongoDB 这类，`/api/schema/columns`
   没有分区列概念。而 Hive / Spark 的分区列对血缘是必需的
   （第一期修复的 `Column 'pt' not found` 正是分区列问题）。
   → **dbx 适合 OLTP 场景；大数据场景仍需 Gravitino（直连 HMS）**。
   → 建议在页面上标注两者的适用范围，避免用户对 Hive 表选了 dbx 却拿不到分区信息。

5. **字段命名不统一**
   同一套 API 里 `camelCase` 与 `snake_case` 混用（如 `connectionId` vs `connection_id`），
   DTO 需按路由分别定义，不能套用统一命名策略。

### 前端

新增「元数据服务」配置页：服务类型选择、地址、凭据、启用开关、优先级、测试连接。
血缘解析页面增加"元数据来源"选择器。

---

## B. 血缘持久化（用户 2026-08-22 明确要求）

### B1. 表结构设计
需产出完整的血缘持久化表结构设计（DDL + 说明）。已有 `sql-tools/bin/ini.sql`
定义了 `tbls` / `columns_v2` / `col_rel` 三张表可作为起点，但需要按下面的新要求扩展。

### B2. 支持多种数据库
持久化层必须可切换后端：
1. **本地数据库**（嵌入式，开箱即用，无需外部依赖 —— 候选 H2 / SQLite）
2. **MySQL**
3. **PostgreSQL**

→ 设计上需要一层 `LineageRepository` 抽象 + 各数据库的 schema 迁移脚本
（建议引入 Flyway 或 Liquibase 管理三套方言的 DDL）。
注意递归查询：MySQL 8.0+ 与 PG 都支持递归 CTE，H2/SQLite 需另行验证。

### B3. 血缘多版本
- 每次解析产生一个版本，**默认查询最新版本**
- 页面上可以设置（切换/指定）版本，也可以删除版本
- 需要版本表 + 版本号/时间戳，以及"设为当前版本"的能力
- 与 A 中的血缘 diff 能力天然契合

---

## C. 多租户（用户 2026-08-22 明确要求）

血缘系统需按**租户（Tenant）+ 项目（Project）**两级隔离：

- 所有血缘数据、元数据服务配置、版本都要带 `tenant_id` / `project_id`
- 查询必须强制带租户过滤，避免越权读到别的租户数据
- **用户体系后期再设计**，本期只需把租户/项目的数据模型和过滤链路打通
- 建议在持久化层统一拦截（如 MyBatis 插件 / JPA Filter），而不是靠每个查询自觉带条件

> 这一项会影响 B 的全部表结构（每张表都要加租户/项目字段），
> 因此 **B 与 C 必须一起设计**，不能先做完 B 再补 C。

---

## D. 可用于开发测试的外部服务（用户提供）

| 服务 | 地址 | 凭据 |
|---|---|---|
| dbx | http://localhost:4224/ | 密码 `123456` |
| Gravitino | http://localhost:8090/ui/metalakes | — |

可用于真实联调 `DbxMetadataProvider` 与 `GravitinoMetadataProvider`。

---

## E. 从第一期 / 第二期顺延的事项

- 血缘持久化 + 全局血缘图（原方案 A）—— 第三期核心
- 批量 / 异步解析 + 结果缓存（原方案 B）
- 转换逻辑（算子表达式）下钻（原方案 C）—— 列级血缘的核心差异化价值，当前被丢弃
- 可观测性：Actuator + Micrometer + 解析耗时/失败率指标（原方案 G）
- 前端重构：Pinia 收拢状态、拆分 852 行的 `LineageGraph`、G6 v4→v5 评估（原方案 H）
- `JdbcMetadataProvider`（第二期确认只抽 SPI 不实现）
- Gravitino 元数据缓存（第二期确认暂不做，如后续多人使用再评估）
