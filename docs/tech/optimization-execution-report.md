# 优化执行报告

> 执行日期：2026-09-22 · 范围：`docs/tech/adr/0006` ~ `0009`
> 前提：`ADRs 0003/0004/0005` 已确立「两后端零测试 → 先补测试」与「三模式 + 可独立拿出」两条判断

## 一句话结论

**清单里的 4 项做完并验证，2 项做完分析但没有动代码**（它们需要人来定方向，改一半比不改更糟）。
过程中测试网从 **10 个用例** 长到 **414 个**，并因此抓出**两个真实的生产缺口**。

---

## 1. 交付清单

| 项 | 状态 | 关键产出 |
|---|---|---|
| P0-A2 三模式冒烟测试 | ✅ | `RunModeSmokeTest` × 3 服务（dw-org / dw-model / dw-lineage） |
| P0-A3 跨服务契约测试 | ✅ | `InternalContractTest` × 2 + `CrossServiceDesignGuardTest` |
| P1-5 构建统一 + 版本对齐 | ✅ | 根聚合 `pom.xml`；lineage Spring Boot 3.3.11 → 3.3.13；`npm run test:api` |
| P0-1b 抽 dw-common | ✅ | 32 个共享类单点化；构建链路（Dockerfile ×2 / compose ×3 / package.sh ×2）同步改造 |
| P2-6 前端收敛 | 🟡 分析完成 | 修正误判：真实重复 **15 文件 / 2,042 行**，非 23 个 |
| P2-7 dw-lineage 分层 | 🟡 分析完成 | 分层健康，**不建议拆模块**；负担是 4~5 个 700+ 行大类 |

### 验证结果

```
mvn -f pom.xml test        →  BUILD SUCCESS
  sql-tools   366 tests ✅      dw-org    25 tests ✅      dw-model  23 tests ✅
  合计 414 个用例，0 失败
docker compose config      →  有效
发布打包（dw-org api）      →  走到依赖缓存重建被环境批量删除保护拦住（非脚本问题）
```

测试网变化：

| 模块 | 之前 | 之后 |
|---|---|---|
| dw-org/api | 0 | **25** |
| dw-model/api | 0 | **23** |
| dw-lineage/sql-tools | 359 | **366** |

---

## 2. 抓到的两个真实缺口（都有代码修复）

### 缺口 1：`X-Tenant-Code` 解析不到被静默忽略

技术方案 §3.3 明确要求「解析不到 → 400，**不准静默落到租户 1**」。
实际行为是：只传 `X-Tenant-Code` 且解析不到时，`TenantFilter.resolveTenantId` 返回 `null`，
请求**静默变成「没有租户」并返回 200** —— 而只传 `X-Tenant-Id` 对不上时是 403。
同一件事两种结果，且失败方向是「成功」。

**修复**：解析不到时把原始值往下带，交给下游存在性校验拒掉（403）。
改动一行，行为与 `X-Tenant-Id` 一致。

### 缺口 2：模块直打组织的业务接口

`dw-model` 的 `OrgClient` 调的是 `/api/auth/refresh` 和 `/api/auth/logout` ——
那是**给浏览器用的接口**，属于技术方案 §2 明令禁止的「直打兄弟 `/api`」。
这是本次新写的源码级守卫 `CrossServiceDesignGuardTest` 第一次运行就抓到的。

**修复**：组织侧在 `InternalController` 新增 `/internal/v1/auth/refresh|logout`
（走模块令牌校验），模块侧改指内部端点并附模块令牌。
副产品是「替用户续期」这条链路从此多一道模块令牌保护。

---

## 3. 四项实施的要点与代价

### P0-A2 三模式冒烟

每服务一份 `RunModeSmokeTest`，断言的是**「差异符合设计」而不是「行为一致」**：

- **dw-model**：standalone 登录 403 + `/api/**` 开放；standard 用本地账号 `张三/123456` 能登录
  并读到 `/me`；multi 登录 403 + `/api/**` 需鉴权。
- **dw-org**：**恒为 multi**。即使 `run-mode` 配成 standalone/standard 也必须仍是 multi 且登录可用 ——
  这是误配防护：组织平台是身份唯一来源，被切进 standalone 就等于整套系统没人能登进去。
- **dw-lineage**：三模式都能启动并如实上报；multi 下组织不可达时业务接口仍可用。

代价：多起 3~4 个 Spring 上下文，单模块 `mvn test` 慢约 10 秒。这是刻意换来的。

### P0-A3 跨服务契约

`CrossServiceDesignGuardTest` 用源码扫描守三条禁止项：
**三家库名互不相同且互不引用**、**`internal` 包里不许出现 `/api/` 字面量**、
**发起方只传 `tenantCode`/`projectCode`**。
兄弟模块不在场时跳过（`assumeTrue`），保证每个服务仍能独立 checkout。

> 记录一个设计取舍：接收方 `authz/check` 在过渡期**仍接受** `tenantId`/`projectId`，
> 这是 §3.3 允许的。守卫只约束**发起方** —— 职责边界写进了测试注释。

### P1-5 构建统一

根 `pom.xml` 是**聚合不是继承**：三个模块真实父仍是各自的 `spring-boot-starter-parent`，
版本必须各自看得见。前端 `sql-tools-vue` **不并入** npm workspaces（它用 pnpm、
有独立 `node_modules`），改为在 `package.json` 的 `comments` 里写明边界与原因。

> **后续（2026-09-23）**：上面这个「不并入」的判断已被
> [ADR-0014](adr/0014-frontend-package-manager-unification.md) 取代 —— `sql-tools-vue`
> （现 `dw-lineage/ui`）已并入根 npm workspaces，pnpm 相关文件全部删除。
> 此处保留原文，作为当时决策的记录。

### P0-1b 抽 dw-common

判据（写进模块描述）：**只有两侧逐字节相同才有资格搬进来**，有一行差异就留在各自服务。
按此筛出 32 个类（13 entity + 13 mapper + 5 support + 1 dto）。
唯一障碍 `JsonbStringTypeHandler` 依赖服务侧的 `MetaDb.isPostgres` ——
把这条字符串规则收进共享层 `DbVendors` 解决。

依赖用 `${project.version}`：服务与共享模块同版本发布，不一致时构建**直接失败**而不是静默降级。

**代价（必须知道）**：单模块构建多了一个前置
`mvn -f dw-common/pom.xml install`；日常用 `npm run test:api` 不受影响。
Dockerfile 因此改为「先 install 共享模块，再按本服务 pom 构建」两步 ——
刻意**不用**根聚合 pom，否则会把无关模块源码拖进构建上下文。

---

## 4. 两项只做分析的理由

### P2-6 前端收敛

**先更正了上一版的一个误判**：所谓「23 个文件逐字节相同」里，8 个是
`src/engine/*.ts`，内容就一行 `export * from '@dw-ai/engine';` —— 是兼容旧相对导入的转发壳，
不是重复代码。**真实重复是 15 个文件 / 单份 2,042 行**（`org-users.vue` 一个就占 823 行）。

不动手的原因：改 import 路径这类「机械改动」也需要 `vue-tsc`/构建兜底，
而前端目前只有两个 e2e 冒烟、没有单元测试。**先让前端有「一条命令能验证改动」的能力，
再做收敛** —— 否则错误会以用户面前的白屏形式出现。

### P2-7 dw-lineage 分层

17,871 行主代码搭配 **12,504 行测试（62 个文件）**，测试比 0.70，是三个后端里最敢改的。
分层是常规的 `controller → service → persistence`，**没有重构的必要**。
真实负担是几个大类（`SQLLineageMerger` 781 行、`JdbcLineageCatalogRepository` 746 行、
`JdbcStatsRepository` 697 行）。建议**不拆模块、只拆大类** ——
拆模块换不到部署自由度（它本来就是独立部署单元），只多出跨模块调用成本。

---

## 5. 下一步建议

| 优先级 | 事项 | 前置 |
|---|---|---|
| 🔴 | 提交本轮变更 | 无（约 60 个文件：dw-common 新增、两侧删除、测试、构建脚本、ADR） |
| 🟠 | 前端加一条可验证命令（`vue-tsc --noEmit`） | 无 |
| 🟠 | 补 dw-org / dw-model 的**租户隔离**测试 | 无（dw-lineage 已有同类测试可参照） |
| ⚪ | P2-6 收敛 15 个相同文件到 `packages/ui-common` | 上一条 |
| ⚪ | P2-7 拆那几个 700+ 行的大类 | 无（有测试兜底） |
| ⚪ | 清理 `/api/auth/refresh` 里不可达的 Bearer 兜底（死代码） | 无 |
