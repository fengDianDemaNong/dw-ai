# ADR-0010：多租户止血 —— 关掉可构造的越权路径

## Status

Accepted

## Context

[多租户设计评审](multi-tenant-review.md) 查出两个严重问题与三个高优先级问题。
其中 **F1 是本次唯一被实测复现的越权**：组织只按**请求头**里的 tenantCode/projectCode 判权，
数据操作按**路径**里的 projectId 落库，而 dw-model 的 multi 分支跳过了唯一校验
「项目属于当前租户」的 `requireProject`。

复现证据（`MultiTenantIsolationTest` 在修复前跑）：
把桩组织平台的 `authz/check` 设为**永远 allow**，一个 alpha 租户的用户请求 beta 租户的项目：

```
读 /api/projects/{beta项目}/domains  → 200，返回 beta 的主题域
写 /api/projects/{beta项目}/domains/{beta的id} → 200，beta 的行被改成 code=HIJACKED
```

也就是说：**组织说「你有这个权限」之后，模块没有任何一步确认「这个操作发生在不属于你的项目上」。**

## Decision

七处代码改动，全部对齐「租户是隔离边界，不是请求装饰」这一条。

### dw-model

| # | 改动 | 关掉的问题 |
|---|---|---|
| 1 | `TenantFilter.resolveTenantId`：解析不到的 code 不再返回 `null`，把原值往下带交给存在性校验拒掉（与 dw-org 同款） | F3：未知 code 静默变成「无租户」的请求，让后续归属校验失去依据 |
| 2 | `AccessService.requireMember` 的 multi 分支先 `requireProject(projectId)`，再问组织 | F1 主路径 |
| 3 | `remoteAuthz` 改为接收**已校验的项目实体**取 code，请求头只作兜底 | F1 的「头覆写路径」不对称 |
| 4 | `AccessService.requireSameProject(projectId, entityProjectId)`；5 处 `save*`（`SpecService` ×3、`TableService` ×2）在按 `in.id()` 取回实体后校验归属 | F1 第二处：IDOR，传别人的资源 id 就能改到别人的行 |
| 5 | `OrgClient` 用 `ClientHttpRequestFactories` 设连接 2s / 读 5s 超时 | F4：org 僵住时模块线程池被拖死 |

### dw-lineage

| # | 改动 | 关掉的问题 |
|---|---|---|
| 6 | `TenantInterceptor`：multi 模式下租户/项目头缺失 → 400，不再回落到默认租户 | F2 的「事故形态」：shell 漏带头时静默读成租户 1 |
| 7 | 编码解析不到 → 400，**删掉自动落户逻辑** | F7：编码写错会静默多出一个租户 |

第 7 条的安全性有一个前提，已核实：前端 `utils/request.ts` 的 axios 拦截器**每次请求前
`await ensureOrgProject()`**，而落户走的是 `/internal/v1/projects/{code}`（不在 `/api/**` 拦截范围内）。
所以正规路径本来就不依赖拦截器里的兜底建租户，删掉它只影响「编码写错」这种情况 —— 而那正是要暴露的。

## Consequences

### 更容易

- **越权路径被关掉**，且带**可证明有牙齿**的回归测试：把改动 2 回退后
  `MultiTenantIsolationTest` 立刻 3 红，响应体里能看到 beta 的数据被读出、被改名。
- multi 模式下的配置错误从「静默读错租户」变成「400 并说明缺什么」。
- org 不可达/僵住时，模块的失败是**有界**的（503 或超时），不会把线程池拖垮。

### 更困难 / 需要接受的代价

- **multi 模式对客户端变严了**：lineage 的 `/api/**` 必须带租户头。
  已核实前端每次都带，且 **`/api/runtime`（模式探测）与 `/api/tenants` 等跨租户管理面已豁免** ——
  前者不知道模式就不知道该不该带头（循环依赖），后者作用对象由路径参数指定、本就不读租户上下文。
- **数字本地 id 仍然通过**。这是刻意的取舍：跨租户管理面与「无项目上下文」的页面依赖它，
  收紧会连带打断这些既有用法。因此第 6 条只关掉了**完全缺头**这一种形态。
- dw-model 的 5 处 `save*` 多了一次 `project_id` 比较（无额外查询，实体已加载）。

### 没有关掉的（必须说清）

- **F2 的完整形态没有解决。** 第 6 条只挡住了「不带头的意外」，挡不住**主动带一个
  不属于自己的头**：multi 下，一个 alpha 租户的合法用户带上 `X-Tenant-Code: beta`
  仍能读到 beta 的数据 —— 令牌回答的是「你是谁」，不是「你能碰哪个租户」。
  真正的解法是给 lineage 接上组织签发的身份 —— 那需要定「用组织 JWT 还是模块令牌」，
  且要联调 iframe 嵌入链路，属于独立决策（评审里记为 R7）。
  （standalone 的「任何能访问端口的人都能读写任意租户」仍然成立，那是刻意的无认证形态；
  standard 已另行收紧：租户头被 `TenantInterceptor` 整条忽略，请求永远落在默认租户上。）
- **F5（对称共享 JWT 密钥）**：模块仍持有签发密钥。生产 multi 应走 Casdoor/OIDC（RS256 + JWKS）。
  这是部署决策，不是代码问题。
- **F8（fan-out 无删除对账）**：模块离线期间被删的项目仍会留成幽灵。
  修它要新增一条「模块上报自己有哪些 project_code，org 差集删除」的协议，单独立项。
- **R11（租户进 JWT）**：这是唯一能**结构性**消灭 F1/F3/F7 这类问题的改法 ——
  从「每个端点记得校验」变成「拿不到租户声明就构造不出请求」。改动会波及前端切租户流程，需先出设计。

## 后续

1. 给 dw-org / dw-model 补**租户隔离测试**并纳入 CI（本次只覆盖了 dw-model 的 multi 分支；
   dw-org 的 `requireProject` 覆盖较全，但没有测试守着）。
2. 评审里 R1~R5 已全部落地；R6 的超时已做，**authz 结果缓存**未做（会让「刚被收回的权限」在窗口内仍生效，需要产品确认）。
3. 建议按 R7 → R11 的顺序推进：先给 lineage 补身份，再考虑把租户放进 JWT。
