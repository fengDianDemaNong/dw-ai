# 代码库优化方案（架构审查）

> 审查日期：2026-09-21 · 审查范围：仓库根 `dw-ai` 全部模块
> 原则：只诊断 + 出方案，不改代码。每个方案标注优先级与代价/收益。

## 执行进度

| 项 | 状态 | 说明 |
|---|---|---|
| P1-3 统一 dw-lineage 命名与结构 | ✅ 已完成 | 包名 `org.qq` → `com.dwai.lineage`；groupId `org.example` → `com.dwai`；360 个测试全绿。见 `dw-lineage/docs/adr/0001-unify-package-name.md` |
| P1-4 Flyway 脚本收敛为单一来源 | ✅ 已完成 | `db/migration` 为唯一来源，`release/sql` 的 01/02 删除、打包时派生；实际打包验证派生逐字节一致。见 `dw-lineage/docs/adr/0002-schema-single-source.md` |
| P0-1 消除 dw-org / dw-model 重复 | ✅ 已完成 | 先清理 16 个建模死代码（14 表 ↔ 14 entity）；后抽 `dw-common`（32 个逐字节相同的类）。见 `docs/tech/adr/0003`、`docs/tech/adr/0008` |
| P0-A 补最小回归网 | ✅ 已完成 | dw-org 6 → 25 个测试、dw-model 4 → 23 个。见 `docs/tech/adr/0004-add-minimal-test-suite.md` |
| P0-A2 三模式冒烟测试 | ✅ 已完成 | 每服务一份 `RunModeSmokeTest`：dw-org 恒 multi（误配防护）、dw-model 三模式差异、lineage 三模式可启动。共 25 个用例。见 `docs/tech/adr/0006` |
| P0-A3 跨服务契约测试 | ✅ 已完成 | `InternalContractTest` ×2 + `CrossServiceDesignGuardTest`（源码级守 §2 三条禁止项）。**修掉两个真实缺口**：`X-Tenant-Code` 静默忽略、模块直打组织 `/api/auth/*`。见 `docs/tech/adr/0006` |
| P1-5 构建统一 + 版本对齐 | ✅ 已完成 | lineage 3.3.11 → 3.3.13；新增根聚合 pom；`npm run test:api` 一条命令构建全部后端。见 `docs/tech/adr/0007` |
| P0-1b 抽 dw-common | ✅ 已完成 | 32 个类单点化 + 构建链路（Dockerfile ×2 / compose ×3 / package.sh ×2）同步改造。见 `docs/tech/adr/0008` |
| P2-6 前端收敛 | 🟡 分析完成，待决策 | 修正误判：真实重复是 **15 个文件 / 2,042 行**（`src/engine/*` 是转发壳，非重复）。需要先让前端有可验证命令。见 `docs/tech/adr/0009` |
| P2-7 dw-lineage 分层体检 | 🟡 分析完成，待决策 | 分层健康、**不建议拆模块**；真实负担是 4~5 个 700+ 行的大类。见 `docs/tech/adr/0009` |
| P0-2 提交变更 | ⏳ 由用户处理 | 用户已提交两轮（`调整目录结构`）；本轮产出待提交 |

> **进展更新（2026-09-21）**：P0-A 已完成 —— dw-org/api 与 dw-model/api 补上了最小回归网
> （dw-org 6 个测试、dw-model 4 个测试，全部通过）。见 `docs/tech/adr/0004-add-minimal-test-suite.md`。

---

## ★ 复审结论（第二次审视，2026-09-21）

**修正一个判断：最高优先级不是「消除重复」，而是「两个后端零测试」。**

### 关键指标

| 模块 | 主代码 | 测试 | 判定 |
|---|---|---|---|
| dw-org/api | 69 文件 / 5,981 行 | **0** | 🔴 盲区 |
| dw-model/api | 88 文件 / 6,834 行 | **0** | 🔴 盲区 |
| dw-lineage/sql-tools | 173 文件 / 17,871 行 | 65 文件 | 🟢 有保障 |

### 复核新增的发现

1. **两个后端完全没有测试**（`src/test` 不存在），却承载「租户管理」「智仓」的全部核心业务。
   这是所有重构（P0-1 抽取、P2-6 前端收敛）迟迟推不动的**根因** —— 改完没有任何东西能告诉你
   改坏了没有。dw-lineage 有 65 个测试文件（含架构守卫 `TenantIsolationArchTest`），
   是当前唯一「敢改」的模块。
2. **Spring Boot 版本不统一**：dw-org/dw-model 是 3.3.13，dw-lineage 是 3.3.11。
3. **前端不是简单的重复**：dw-org/ui 与 dw-model/ui 共享 23 个逐字节相同文件，
   另有 28 个同源但**已分叉**（diff 从 4 行到 406 行）—— 收敛前必须先判断哪些是真业务差异。
4. **构建入口分散**：仓库根没有聚合 pom，三个后端各自 `mvn -f <path>/pom.xml` 独立构建；
   `sql-tools-vue` 不在根 workspace，另两个 ui 在。这个结构会放大跨模块改动的成本
   （这正是 P0-1 抽取被回退的直接原因）。

### 修正后的优先级

| 序 | 项 | 理由 |
|---|---|---|
| 1 | **P0-2 提交当前变更**（351 条） | 重组 + 新模块 + 本轮改动全部悬空，先止血，建立可回滚基线 |
| 2 | **P0-A 给 dw-org / dw-model 补最小测试** | 一切重构的前提。优先覆盖：Flyway 能建库、鉴权链路、项目 CRUD 三类冒烟 |
| 3 | P1-5 构建统一 + 版本对齐（3.3.11 → 3.3.13） | 降低跨模块改动成本，为第 4 步铺路 |
| 4 | P0-1 续做（抽 dw-common） | 补完测试 + 构建统一后再动，成本显著下降、有回归保护 |
| 5 | P2-6 前端收敛 | 先区分 23 相同 / 28 分叉，只抽真正共享的部分 |
| 6 | P2-7 dw-lineage 分层体检 | 体量大但已有测试，风险最低，可最后做 |

> ⚠️ **重要补充（2026-09-21）**：项目负责人澄清了架构约束 ——
> org 做租户、model 做建模、lineage 做数据地图，后两者及后续服务都设计为**可独立拆出**，
> 有 `multi` / `standard` / `standalone` 三种模式，**只有 multi 才与 org 整合**。
> 这**修正了本方案对「重复」的定性**：32 个组织与平台类不是纯技术债，
> 而是「每个服务能独立拿出用」的必要条件。
> 由此新增两项、并调整一项优先级：
>
> | 新增 | 说明 |
> |---|---|
> | **P0-A2 三模式冒烟测试** | 每服务在三种模式下都要能启动 —— 「可独立拿出用」唯一可验证的方式 |
> | **P0-A3 跨服务契约测试** | 固化设计禁令：不共库、不直打兄弟 `/api`、不用组织主键当 code、`X-Tenant-Code` 解析不到必须 400（不得静默落租户 1） |
>
> 详见 `docs/tech/adr/0005-three-modes-and-independence-tradeoff.md`。

> 下面「一、现状诊断」「二、优化方案」「三、建议执行顺序」是首次审查原文，
> **优先级以上述复审结论为准**。

---

## 一、现状诊断（带证据）

### 模块规模

| 模块 | Java 行数 | 文件数 | 定位 |
|---|---|---|---|
| dw-org | 6,432 | 172 | 租户管理（ui + api） |
| dw-model | 6,835 | 212 | 智仓（ui + api + rules） |
| dw-lineage | 30,240 | 368 | 数据地图 / 血缘（sql-tools + sql-tools-vue） |
| packages/engine | — | 20 | TS 规则引擎（`@dw-ai/engine`） |
| docs | — | 812 | 产品原型（7 个版本）+ 技术方案 |

### 核心发现

1. **dw-org/api 与 dw-model/api 有 66 个 Java 文件逐字节相同**，9 个有差异。
   重复集中在基础设施层（auth / db / web / scheduler / internal / query / rules / meta/support），
   业务实体（ProjectService / AccessService 等）才是差异所在。
2. **dw-lineage 完全独立**：包名 `org.qq`（另两个是 `com.dwai.platform`），
   从外部仓库 `sql-lineage` 拷入，构建脚本（build-release.sh + ci.sh）与另两个（package.sh）不统一。
3. **前端 dw-org/ui 与 dw-model/ui 有 23 个文件完全相同**（51 个共同文件中 23 个相同）。
4. **Flyway 双轨漂移风险**：`db/migration/` 与 `release/sql/` 是同一份 DDL 的两个副本，靠人工同步。
5. **348 条未提交变更**：`services/` → `dw-org` + `dw-model` 的大规模重组、`dw-lineage` 全新模块，全部未 commit。

---

## 二、优化方案（按优先级）

### P0-1 消除后端 66 文件重复 —— 抽公共模块 `dw-common`

**问题**：两个 api 服务的基础设施层几乎完全重复（66 文件相同）。改一个鉴权 bug 要改两处，
漏改一处就漂移；基础设施演进（鉴权、数据源、Flyway 引导、调度、internal 契约）永远双写。

**方案**：抽 `dw-common`（Java 模块，或 `packages/java-common`），放共享基础设施：
- 鉴权（auth 包）
- 数据源 + Flyway 引导（db 包，`MetaDb` / `MetaDbEnvironmentPostProcessor`）
- Web 层（`SpaIndexController`、全局异常、CORS）
- 调度（scheduler）
- internal 契约、query 工具、meta/support 公共部分

`dw-org/api` 与 `dw-model/api` 通过 Maven 依赖引入，只保留各自业务（meta 实体/mapper/service）。

**代价**：一次性重构 + 需要建立公共模块的版本发布策略（SNAPSHOT 或同一多模块 reactor）。
**收益**：消除双写 bug，基础设施单点演进，两个服务真正收敛为「业务差异」。

> 建议方向：**模块化单体**的共享 lib，而不是拆微服务。团队规模小、边界还在演化，
> 共享 lib 比微服务更务实。公共模块改动影响两个服务，需配套测试兜底。

---

### P0-2 立即提交 348 条未提交变更

**问题**：重组 + 新模块全部未 commit，`git checkout` / `reset` 一旦误操作不可恢复，也无法 review。

**方案**：拆成几个逻辑清晰的 commit，尽快提交：
1. `services/` → `dw-org` + `dw-model` 目录重组（纯移动 + 包路径调整）
2. 引入 `dw-lineage`（新模块）
3. 文档与技术方案更新
4. 构建脚本 / docker-compose 调整

---

### P1-3 统一 dw-lineage 命名与结构

**问题**：包名 `org.qq` 与项目统一的 `com.dwai.platform` 不一致，是外部仓库残留。

**方案**：包名重命名 `org.qq` → `com.dwai.lineage`（纯重命名，IDE 重构 + 全量测试回归，低风险）。
同时把构建脚本、Dockerfile、配置注释里的 `sql-lineage` / `org.example` 等残留一并收敛。

**代价**：一次全量回归测试；若依赖了 `org.qq` 的序列化类全限定名（很少见）需排查。
**收益**：命名统一，降低认知成本。

---

### P1-4 Flyway 脚本收敛为单一来源

**问题**：`db/migration/<方言>/V*.sql` 与 `release/sql/<方言>/01/02_*.sql` 是同一份 DDL 的两个副本，
靠人工同步（改表结构要改两处 + `upgrade/`），历史上已因此漂移过。

**方案（三选一，按推荐度）**：
1. **init-db.sh 直接调 Flyway**：离线建库脚本改为调用应用内的 Flyway `migrate` 命令，
   `release/sql/` 退化为只读快照或直接删除，彻底单一来源。
2. 保留双副本，但加 CI 校验：一个脚本在 CI 里 `diff` 两处，不一致就 fail（治标）。
3. `release/sql/` 由 `db/migration/` 生成（类似 comments.json → gen 脚本的做法，已有先例）。

> 推荐 1：与 dw-org / dw-model 的「纯 Flyway」模式彻底对齐，消除双轨。

---

### P1-5 统一构建 / 发布 / CI

**问题**：dw-org/dw-model 用 `package.sh`，dw-lineage 用 `build-release.sh` + `ci.sh`，
三个模块各一份 `docker-compose.yml` / `Dockerfile` / `nginx.conf`。

**方案**：仓库根统一一个构建入口（或一套模板），各模块只维护差异配置（端口、库名、镜像名）。

---

### P2-6 前端 ui 重复收敛

**问题**：dw-org/ui 与 dw-model/ui 有 23 个文件完全相同（布局、鉴权、通用组件）。

**方案**：仿照 `packages/engine` 的成功做法，抽 `packages/ui-common`（共享布局、鉴权、通用组件），
两个 ui 通过 workspace 依赖引入。

---

### P2-7 dw-lineage 体量与内部分层

**问题**：30,240 行 Java 单体，是 dw-org + dw-model 之和的 2 倍多。

**方案**：先做内部分层体检（controller / service / repository 边界是否清晰、
有无上帝类、DTO 是否过多），再决定是否按 bounded context 拆分
（血缘解析 / 元数据目录 / 租户管理 天然是三个候选边界）。短期不拆，先靠测试守住行为。

---

## 三、建议执行顺序

1. **立即**：P0-2 提交未提交变更（止血，保护工作成果）
2. **本迭代**：P0-1 抽 dw-common（收益最大、风险最高的重构，单独一个 PR + 全量回归）
3. **随后**：P1-3 命名统一 → P1-4 Flyway 单一来源 → P1-5 构建统一
4. **择机**：P2-6 / P2-7

> 核心判断：当前最大的技术债不是「少了什么」，而是「多了一份」——
> 两份后端、两份前端、两份 DDL、三份构建脚本。收敛重复比新增抽象优先。
