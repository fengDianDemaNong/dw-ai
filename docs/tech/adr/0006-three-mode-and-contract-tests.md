# ADR-0006：把三模式与跨服务契约变成可执行回归网

## Status

Accepted

## Context

技术方案 `docs/tech/07-0.2.0.md` 里有一批**硬约束**，此前全部只存在于文档中，没有一行测试守着：

- §3.3 三种启动模式（`standalone` / `standard` / `multi`）各自的请求头与鉴权要求；
- §4 各模式鉴权方式不同（独立放行 / 本模块账号 / 组织远程 authz）；
- §3.5 模块只能走 `/internal/v1/**`，且必须带模块令牌；
- §3.2 跨服务只认 `code`，不认任何一边的主键；
- §3.3 「`X-Tenant-Code` 解析不到 → 400，**不准静默落到租户 1**」；
- §2 禁止项：不共库、不直打兄弟业务 `/api`。

这些约束有个共同的失效形态：**违反之后单个服务各自都「正常」**。

- 某模式起不来 → 只有用那个模式的客户才会遇到；
- 静默落到默认租户 → 表现是完全正常的 200，只有数据串了才被发现；
- 模块直打兄弟 `/api` → 本机能跑，等到对方改字段才炸。

而当时 dw-org / dw-model **一个测试都没有**（见 ADR-0004），dw-lineage 有 65 个测试文件
但从没验证过「三种模式都能起来」。也就是说，产品最重要的一条承诺 ——
**「每个服务都能单独拿出来用」** —— 当时没有任何自动化的证据。

## Decision

分两层补上回归网。

### 1. 三模式冒烟（每服务一份 `RunModeSmokeTest`）

用 `@Nested` 让三种模式各起一次 Spring 上下文，断言的不是「行为一致」而是**「差异符合设计」**：

| 服务 | 断言内容 |
|---|---|
| dw-model | standalone：登录 403、`/api/**` 开放；standard：本地账号 `张三/123456` 可登录并读到 `/me`、`/api/**` 需鉴权；multi：登录 403、`/api/**` 需鉴权 |
| dw-org | **恒为 multi**：即使把 `run-mode` 配成 standalone/standard 也必须仍是 multi 且登录可用 |
| dw-lineage | 三模式都能启动且 `/api/runtime` 如实上报；multi 下组织不可达时仍能服务业务接口 |

dw-org 那条是**误配防护**：组织平台是身份唯一来源，它要是被切进 standalone，登录口就关了，
整套系统没人能登进去 —— 而运维手上通常只有一份通用部署模板，很容易把模块的
`DW_AI_RUN_MODE` 原样套到组织进程上。产品规格也写明了「组织平台不做独立模式」
（`product/versions/0.2.0/PRD.md`）。

### 2. 跨服务契约（`InternalContractTest` ×2 + `CrossServiceDesignGuardTest`）

- **dw-org / dw-model 的 `InternalContractTest`**：模块令牌（缺 / 错 → 401）、心跳必填字段、
  `authz/check` 对未同步 code **必须拒绝**且给出原因、`code` 优先于 legacy 主键、
  未登记 code 与已登记 code 的行为差异。
- **`CrossServiceDesignGuardTest`**：扫源码守三条禁止项 —— 三家库名互不相同且互不引用、
  `internal` 包里不许出现 `/api/` 字面量、发起方只传 `tenantCode`/`projectCode`。
  兄弟模块不在场时 `assumeTrue` 跳过，保证每个服务仍能独立 checkout。

## Consequences

### 更容易

- **「可独立拿出使用」变成 `mvn test` 就能验证的事**，不再依赖手工跑三种模式。
- 三模式的行为差异被写明在测试里。后来者看到 `login 403` 的断言，会先去看设计文档
  而不是「顺手修一下」—— 那正是最容易破坏设计的方式。
- 构建期就能发现「模块又去打兄弟业务接口」这类跨进程问题。

### 更困难 / 需要接受的代价

- 三个 `RunModeSmokeTest` 会各起 3~4 个 Spring 上下文，单次 `mvn test` 变慢约 10 秒/模块。
  这是刻意换来的：模式相关的装配问题只有真起上下文才暴露。
- `CrossServiceDesignGuardTest` 用源码扫描，对目录结构敏感（写死了模块相对路径）。
  换来的是能守住单测碰不到的跨服务约束。目录大改时要同步更新它，跳过条件保证不会误红。
- 守卫用的是「路径字面量」这种粗粒度判据，可能误报。如果将来确有例外，
  应当**显式加白名单并写明理由**，而不是放宽判据。

## 实施中发现的真实缺口

1. **`X-Tenant-Code` 解析不到被静默忽略**（dw-org `TenantFilter.resolveTenantId`）。
   只传 `X-Tenant-Code` 且解析不到时，原实现 `return headerTenant`（此时为 `null`），
   请求会「变成没有租户」并返回 200 —— 与只传 `X-Tenant-Id` 对不上时返回 403 的行为不一致，
   正是 §3.3 要禁止的形态。**已修**：解析不到时把原始值往下带，交给下游存在性校验拒掉（403）。
2. **dw-model 直打组织的业务接口**：`OrgClient` 调的是 `/api/auth/refresh` 与 `/api/auth/logout`
   （浏览器接口），属于 §2 明令禁止的「直打兄弟 `/api`」。**已修**：组织侧在
   `InternalController` 新增 `/internal/v1/auth/refresh|logout`（走模块令牌校验），
   模块侧改指内部端点并附模块令牌。副产品是续期/注销从此多一道模块令牌保护。

   注：接收方 `authz/check` 在过渡期**仍接受** `tenantId`/`projectId` 作为兼容参数，
   这是刻意的（见 §3.3「过渡：继续收 `X-Tenant-Id`」）。守卫只约束**发起方**。

3. **`/api/auth/refresh` 的 `Authorization: Bearer <模块令牌>` 分支实际不可达**：
   配了 JWT 解码器时，非法 Bearer 会在安全链上直接 401，到不了控制器里的 `bearer(...)`
   兜底。因此测试只断言 `X-Module-Token` 那条路。那个兜底是死代码，可择机清理（未动）。

## 后续

- 租户隔离测试（dw-org / dw-model 仍缺，dw-lineage 已有 `TenantIsolationApiTest`）。
- 项目 CRUD 与 `/internal/v1/projects/{code}` 的双向一致性（当前只测了模块侧的接收）。
- 若将来组织侧收紧「解析不到 → 400」（当前是 403），把断言从 `<>= 400` 收紧即可，
  测试中已注明该处的取舍。
