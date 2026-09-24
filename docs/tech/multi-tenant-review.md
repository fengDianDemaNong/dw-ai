# 多租户设计评审：租户模型与跨服务交互

> 评审日期：2026-09-22 · 范围：多租户（`multi`）模式下的租户设计、上下文传递、跨服务交互
> 证据等级标注：**[读证]** = 由源码调用链确认；**[实测]** = 有测试复现；**[推断]** = 由结构推出、未复现
> 配合阅读：`docs/tech/07-0.2.0.md` §2 §3 §4、`docs/tech/adr/0005`、`docs/tech/adr/0006`

---

## 结论

**架构意图是对的，落地方式偏弱 —— 问题不在"想错了"，在"靠自觉"。**

三条核心意图都成立，且都有实现：

1. **组织平台是唯一身份源** —— 模块在 multi 下不签发身份（`login` 返回 403），
   且有测试守着（`dw-model RunModeSmokeTest$Multi`）。
2. **跨进程只认 code** —— 边界明确，且已有源码级守卫（`CrossServiceDesignGuardTest`）。
3. **不共库** —— 三个服务三套库名，各有自己的 Flyway 迁移。

弱点出在**隔离的执行方式**上：

> **租户作用域是「请求参数」，不是「可信事实」。**

JWT 里**没有租户声明**（只有 `sub` / `username` / `name` / `platform_admin` / `boot`）〔[读证]〕。
租户完全来自请求头，是否可信**取决于每个端点有没有记得校验**。
于是隔离强度在不同服务之间差了一个量级：

| 服务 | 隔离兜底 | 结论 |
|---|---|---|
| dw-org | `requireTenant()` / `requireProject()`（后者校验项目属于当前租户） | 覆盖较全 ✅ |
| dw-lineage | `TenantContextHolder.require()` 缺上下文即抛异常 + 仓储层显式过滤 + **`TenantIsolationArchTest` 架构守卫** | 纪律最好 ✅ |
| dw-model | multi 分支**跳过** `requireProject`，且**没有 MyBatis 租户拦截器**、**没有租户隔离测试** | **口子在这里** 🔴 |

这不是理论担忧：下面 F1 是一条**可构造的跨租户读写路径**。

---

## 一、设计本身合理的地方（先说清楚哪些别动）

| # | 设计 | 为什么合理 |
|---|---|---|
| A1 | 模块在 multi 下**不提供登录**，身份由 org 统一签发 | 单一身份源。模块若也能登录，就出现第二条身份来源，"谁是真的"立刻无解 |
| A2 | 写路径远程 authz **fail-closed**：org 不可用 → `503` 而不是放行 | 多租户下"授权不可判定时拒绝"是正确方向。可用性换安全，这个方向的错可以接受 |
| A3 | `standalone` / `standard` 真的自足（自带账号、不调 org、不登记） | 有测试证明三种模式都能起（ADR-0006）。"每个服务能单独拿出来用"这条承诺是真的 |
| A4 | 跨服务只传 **code**，且组织主键 `t-<code>` 与 code 是两个东西 | 避免"A 库的 1 和 B 库的 1 是同一个租户"这种经典错误 |
| A5 | 模块**不持有组织库**，靠 `/internal/v1/projects/{code}` 落镜像 | 边界干净；项目列表在模块侧是只读副本 |
| A6 | lineage 的 `TenantContextHolder` 提供跨线程 `wrap()`，且 `require()` **拒绝默认兜底** | 明确写了"静默降级到默认值会造成跨租户串数据，宁可失败"。这是全仓最好的隔离纪律 |
| A7 | 服务注册表**落库并在重启时载入**〔[读证] `ServiceRegistry.run()`〕 | 不是纯内存——重启不会丢注册信息（我原本怀疑这点，实测否掉了） |

---

## 二、身份与作用域模型

### 三套租户标识体系

| 服务 | 租户主键 | 项目主键 | 表名 |
|---|---|---|---|
| dw-org | `VARCHAR` = **`t-` + code**（主键由编码派生） | `VARCHAR` | `tenants` / `projects` |
| dw-model | 同上（镜像自 org） | 同上（镜像自 org） | `tenants` / `projects` |
| dw-lineage | **`BIGINT` 自增**（默认 `1`，code=`default`） | `BIGINT` 自增（默认 `1`） | **`tenant` / `project`**（单数） |

三者靠 `code` 互相翻译。**这是可用但脆弱的设计**：任何一处把 id 当 code 或反之，就会静默落到另一个租户或新建一个租户（见 F7）。

另外注意 dw-org 的选择：主键 = `t-` + code，意味着 **code 一旦确定就不能改**（改了要级联改所有外键），
且 `id` 与 `code` 之间只差一个前缀 —— 这削弱了"两者是不同的东西"这条纪律的说服力。
若要迁移到代理键，现在改成本最低。

### 请求头 → 上下文

```
浏览器壳 ──X-Tenant-Code / X-Project-Code / X-Tenant-Id(legacy)──► 模块
                                                                  │
                              JWT(无租户声明) ────────────────────┘
                                                                  ▼
                                            TenantFilter / TenantInterceptor
                                                                  ▼
                                            TenantContext(ThreadLocal)
                                                        │ finally clear()
                                                        ▼
                                              业务方法自行校验租户归属
```

**JWT 不含租户**是这套模型的关键特征。好的一面：一个用户可在多租户间切换而无需重新登录。
代价：**每个端点都必须自己校验"这个租户/项目我能不能碰"**，忘记一次就是一个越权。

---

## 三、跨服务交互与失败模式

### 交互链路

```
                 ┌──────────────── 组织平台 dw-org（身份 + 租户 + 项目权威）────────────────┐
                 │  /internal/v1/registry/heartbeat   模块登记（product + baseUrl + 版本）  │
                 │  /internal/v1/authz/check          userId+tenantCode+projectCode+action   │
                 │  /internal/v1/context              校验模块令牌，回上下文摘要             │
                 │  /internal/v1/projects/{code}  ◄── 模块反向推送？（当前是 org → 模块 PUT）│
                 └───────▲──────────────────────────────▲──────────────────────────────────┘
                         │ 心跳(30s/…)+authz/check       │ 心跳 + authz/check
                         │ 项目镜像 PUT（catch-up 推全量）│ 项目镜像 PUT（catch-up 推全量）
                 ┌───────┴────────┐              ┌───────┴────────┐
                 │ 仓建设 dw-model │              │ 数据地图 lineage│
                 │ 镜像 projects   │              │ 镜像 project    │
                 │ 镜像 tenants    │              │ 镜像 tenant     │
                 └────────────────┘              └────────────────┘
```

### 失败模式

| 场景 | 当前行为 | 评价 |
|---|---|---|
| org 不可用 + 模块**写**操作 | `SERVICE_UNAVAILABLE`（fail-closed） | ✅ 方向正确 |
| org 不可用 + 模块**读**角色 | `remoteProjectRole` 捕获异常 → 当前项目降级为 `modeler` | ⚠️ fail-open，但只影响"看到哪些项目"，不影响写鉴权〔[读证] 仅 `ProjectService` 两处使用〕 |
| org **挂住**（TCP 通、不响应） | `RestClient.builder()` **未设任何超时** → 请求线程无限阻塞 | 🔴 **F4**：模块线程池会被拖死 |
| 模块离线期间 org 删了项目 | catch-up 只做 PUT，**无删除对账** | 🟡 **F8**：模块留幽灵项目，永远不会被清理 |
| 模块离线期间 org 建了项目 | catch-up 会补推（`seenAt` 超 120s 触发） | ✅ |
| org 重启 | 注册表从库载入 | ✅ |

---

## 四、问题清单（按严重度）

> **修复状态（2026-09-22 更新）**
>
> | 问题 | 状态 |
> |---|---|
> | F1 dw-model 双权威分叉（跨租户读写） | ✅ **已修**，且回退验证过测试有牙齿（见 ADR-0010） |
> | F3 未知 `X-Tenant-Code` 静默忽略 | ✅ 已修（dw-org 与 dw-model 两侧） |
> | F4 跨服务调用无超时 | ✅ 已修（连接 2s / 读 5s） |
> | F7 lineage 未知编码静默建租户 | ✅ 已修（改为 400） |
> | F2 lineage multi 无鉴权 | 🟡 **部分**：缺租户头 → 400 已做；**「带 `X-Tenant-Id: 1` 即可读写租户 1」仍未关** —— 需要给 lineage 接身份，属待决策项（R7） |
> | F5 对称共享 JWT 密钥 | ⏳ 部署决策（生产走 OIDC/RS256） |
> | F6 三套标识体系 / 表名 / 硬编码 | ⏳ 未动 |
> | F8 fan-out 无删除对账 | ⏳ 需新协议，单独立项 |
>
> 实施细节与代价见 [ADR-0010](adr/0010-multi-tenant-hardening.md)。

### 🔴 F1（严重）dw-model multi 模式：鉴权用「头里的项目」，数据操作用「路径里的项目」

**调用链**〔[读证]〕：

```java
// AccessService.requireMember —— multi 分支直接返回，不做租户归属校验
if (props.isWarehouseOnly() && props.isMulti()) { remoteAuthz(projectId, perm); return; }
//                                                  ↑ projectId 只是"备选"

// AccessService.remoteAuthz —— 头的 code 优先于路径的项目
String tenantCode  = firstNonBlank(TenantContext.tenantCode(),  tenantCodeOf(TenantContext.tenantId()));
String projectCode = firstNonBlank(TenantContext.projectCode(), projectCodeOf(projectId));  // ← 头的优先
org.check(user, tenantCode, projectCode, "warehouse", perm);   // 授权的是"头里的项目"

// SpecService.saveDomain —— 真正落库用的是路径里的 projectId，且无租户过滤
access.requireMember(projectId, "spec:write");
domains.selectCount(...eq(DomainEntity::getProjectId, projectId)...);   // 只按 project_id 过滤
```

**三个条件同时成立**：

1. `AccessService.requireProject()`（唯一做"项目属于当前租户"校验的方法）**在 multi 分支里被跳过**；
2. dw-model **没有** MyBatis-Plus `TenantLineInnerInterceptor`（无自动 `tenant_id` 注入）；
3. `domains` 等业务表**没有 `tenant_id` 列**，隔离只经 `project_id`；而模块的 `projects`
   表镜像了**全部租户的项目**〔[读证] `listAllEntities()` = `selectList(null)`〕。

**触发方式**：任意租户的已登录用户，带自己租户/项目的头（通过 authz），
把 URL 里的 `{projectId}` 换成**别的租户**的项目 id →
`GET /api/projects/{victimProjectId}/domains` 读到对方数据；
`PUT /api/projects/{victimProjectId}/domains/{id}` 写入对方数据。

**同类第二处**：`saveDomain` 里 `domains.selectById(in.id())` **不带 project 作用域**，
随后 `updateById(e)` —— 即使不换 URL 项目，传入别人的 domain id 也能改到别人的行
（所有模式都成立，多租户下危害更大）。

**为什么之前没被发现**：dw-org / dw-model **没有租户隔离测试**（我在 ADR-0006 里列为遗留项），
而 lineage 有 `TenantIsolationApiTest` + `TenantIsolationArchTest` 守着同类风险。

### 🔴 F2（严重）dw-lineage 在 multi 模式下**没有任何鉴权**，且缺头即落租户 1

- 依赖里**没有 `spring-boot-starter-security`**，全仓搜 `Authorization` / `Jwt` / `@PreAuthorize` **零命中**〔[读证]〕。
- `/api/**` 全部被 `TenantInterceptor` 覆盖，但该拦截器**只看头、不看身份**；
  且**头缺失时直接返回 `defaultTenantId`（=1）**，**没有任何模式判断**〔[读证]〕：

  ```java
  private long resolveTenant(HttpServletRequest request) {
      String raw = firstHeader(request, "X-Tenant-Code", TENANT_HEADER);
      if (raw == null || raw.isBlank()) return defaultTenantId;   // ← multi 下静默落租户 1
  ```

设计文档 §3.3 对 multi 的要求是：`X-Tenant-Code` **必填**、`Authorization` = 组织签发的 JWT。
**两条都没落地**。而 lineage 有约 30 个写端点（`/api/lineage/save`、`/api/meta/ddl`、…），
所以：**任何能访问该端口的人，不带任何头，就能以租户 1 的身份读写**。

> 我上一轮新增的 lineage multi 冒烟测试里有一条
> `staysUpWhenOrgIsUnreachable`，断言不带头的 `/api/stats/project` 返回 200 ——
> 它的**本意是"org 挂了模块也别倒"**，但副作用是把"匿名访问租户 1"锁成了期望行为。
> 这条断言在补齐鉴权后**必须收紧**。

### 🟠 F3（高）dw-model 与 dw-org 修前同一个缺口：未知 `X-Tenant-Code` 被静默忽略

`dw-model` 的 `resolveTenantId` 与 dw-org 修前**逐字相同**：解析不到时 `return headerTenant`，
只传 code 时为 `null` → 非 standalone 分支里 `if (headerTenant != null)` 不成立 →
**不返回 403，`tenant` 保持 null，请求继续**〔[读证]〕。
F1 正是靠这个"无租户也能过"的状态才走得通。dw-org 侧我已修（ADR-0006），**dw-model 未修**。

### 🟠 F4（高）跨服务调用没有超时、没有熔断、没有缓存

`OrgClient.client()` = `RestClient.builder().baseUrl(...).build()` —— 没有传
`ClientHttpRequestFactory`，Spring 的默认设置里 connect/read timeout 均为 `null`（=无限等待）〔[读证]〕。

后果：org 进程"僵住"（GC 长停顿、网络黑洞、负载打满但端口仍 accept）时，
**模块的每个写请求都会永久占用一个 Tomcat 线程** → 模块自身被拖死，故障从 org 扩散到模块。
且每次写都同步打一次 org，没有短时缓存 → org 的 QPS 随模块写请求线性放大。

### 🟠 F5（高）multi 的信任边界是"谁知道 JWT_SECRET"

- org 与 model 的 `dwai.security.jwt-secret` **默认值相同**
  （`dw-ai-dev-secret-change-me-please-32b`）〔[读证]〕；
- 模块用**同一个对称密钥**校验 org 签发的 JWT；
- 即：**模块持有签发密钥**。模块被攻破 = 可以给任意租户、任意用户伪造身份。

这与"模块不签发身份"的设计意图相冲突。注意代码里已经有正确做法：
Casdoor/OIDC 路径用 `NimbusJwtDecoder.withJwkSetUri()`（RS256 + JWKS，模块只持公钥）。
**dev/multi 默认路径是弱路径，生产必须走 OIDC 或换非对称**。

附带一点（可接受但要知道）：multi 下模块**跳过 boot 世代校验**〔[读证]〕，
所以 org 侧注销/踢下线不会让模块里的 access token 立即失效，要等过期。

### 🟡 F6 三套标识体系 + 表名不一致 + 硬编码租户

- `t-<code>`（org/model）vs `BIGINT` 自增（lineage）；
- 表名 `tenants` / `projects`（org/model）vs `tenant` / `project`（lineage）；
- `DwaiProperties.implicitTenantId()` 里**硬编码** `"t-xinghe"`〔[读证]〕——
  一个具体客户的名字出现在两个服务的共享类里。它同时是 standalone/standard 下自动创建的本地租户 id。

这些不致命，但每一条都在增加"翻译错误"的概率，而翻译错误的后果是跨租户。

### 🟡 F7 lineage 会把 org 风格的主键当成 code，静默**新建租户**

`TenantInterceptor.resolveTenant`：非数字头按 code 解析，解析不到 → `provisionOrg(v, ...)`
**直接建一个 code = 该字符串的租户**〔[读证]〕。

于是任何客户端若按 org 的习惯传 `X-Tenant-Id: t-xinghe`，lineage 会
`Long.parseLong("t-xinghe")` 失败 → 当 code 找 → 找不到 → **静默创建 code 为 `t-xinghe` 的租户**，
数据从此落在一个人造的租户下。当前前端用 `#boot=` 传 code 所以没踩到，
但这是**只靠客户端守规矩**的设计。

### 🟡 F8 fan-out 没有删除对账

`syncProjectsTo`（心跳 catch-up）只做 PUT，没有"模块里有、org 里没有 → 删掉"的收敛〔[读证]〕。
模块离线期间被删的项目会成为**永久幽灵**。`removeProject` 只在删除动作发生时推送，
错过那一刻就再没有补的机会。

---

## 五、建议（按取舍排序）

### 立刻（低风险、高收益，建议本轮就做）

| # | 动作 | 收益 | 代价 |
|---|---|---|---|
| R1 | `dw-model TenantFilter.resolveTenantId` 改为 `return headerTenant != null ? headerTenant : tenantCode;`（与 dw-org 同款一行） | 未知 code 从"静默无租户"变为 403 | 极小。行为变更需一次回归（已有 23 个测试） |
| R2 | `AccessService.requireMember` 的 multi 分支**先调 `requireProject(projectId)`** | 关闭 F1 的跨租户路径。这一行让"路径项目"必须先通过租户归属校验 | 极小。可能暴露前端此前传错 projectId 的隐藏问题（那正是要暴露的） |
| R3 | `remoteAuthz` 不再优先头的 `projectCode`，改为**用已校验的 project 实体取 code** | 消除"头覆写路径"这条不对称 | 小。语义更严格：头与路径不一致时以路径为准 |
| R4 | `saveDomain`/`saveLayer`/… 对 `in.id()` 增加 `projectId` 归属校验 | 关闭 IDOR 类问题 | 小。需逐个实体方法补，建议抽一个 `requireOwned(entity, projectId)` 工具 |
| R5 | 给 dw-org / dw-model 补**租户隔离测试**（照抄 lineage 的 `TenantIsolationApiTest` 思路） | 把 R1~R4 固化成可回归的约束 | 中等工作量，但这是唯一能让后续重构安全的前提 |

### 短期（需要一点设计决定）

| # | 动作 | 取舍 |
|---|---|---|
| R6 | `OrgClient` 加**连接/读超时**（如 2s/5s）+ 短时（3~5s）authz 结果缓存 | 换来故障隔离；代价是"刚被收回权限"在缓存窗口内仍可用。**建议超时先做，缓存后议** —— 缓存的权限语义变化需要产品确认 |
| R7 | lineage 补齐 multi 鉴权：至少加**模块令牌/组织 JWT 校验** + `X-Tenant-Code` 必填（缺头 → 400） | 这是把 lineage 从"靠网络隔离"变成"自己守门"。代价是 iframe 嵌入链路要一起改，需要一次联调 |
| R8 | 生产 multi 禁用对称密钥：走 Casdoor/OIDC（RS256 + JWKS），或让 org 持私钥、模块用公钥 | 消除"模块可伪造身份"。代价是部署复杂度上升（多一个 IdP 或密钥分发） |
| R9 | fan-out 增加**全量对账**（模块上报自己有哪些 project_code，org 差集删除） | 消除幽灵项目。代价是多一次往返协议 |
| R10 | 抽掉 `implicitTenantId()` 的硬编码，改为配置项（默认 `local` 而非客户名） | 消除客户名硬编码，也顺手让 lineage/org 对"本地租户"有统一约定 |

### 中期（结构性，建议单独立项）

| # | 动作 | 说明 |
|---|---|---|
| R11 | **让租户成为可信事实**：JWT 增加 `tenant_code` 声明（或在 org 侧签发"租户作用域 token"），模块只认 token 里的租户 | 这是唯一能**结构性**消除 F1/F3/F7 这类问题的改法 —— 从"每个端点记得校验"变成"拿不到租户声明就无法构造请求"。代价是切换租户要换 token，前端流程要改。**收益最大、改动最大，建议先出设计再定** |
| R12 | 统一租户标识与表名（lineage 向 `t-<code>` / 复数表名收敛，或反向统一） | 消除翻译层，降低长期出错概率 |

---

## 六、需要你决策的三个点

1. **R7（lineage 补鉴权）的时机**：它是当前最直白的暴露面（无鉴权 + 落租户 1），
   但改动会牵动 iframe 嵌入链路。是"本轮就收紧、接受一次联调"，还是"先记为已知风险、下个迭代做"？
2. **R6 要不要连缓存一起做**：authz 结果缓存能把 org 的 QPS 压下来，但会让"刚被收回的权限"在窗口内仍生效。**超时与缓存建议分开决策**。
3. **R11 是否立项**：把租户放进 JWT 是根治方案，但会改前端"切租户不换 token"的现状。要不要现在就排？

---

## 附：证据索引

| 结论 | 位置 |
|---|---|
| JWT 不含租户 | `dw-org/.../auth/JwtIssuer.java:26-36` |
| multi 分支跳过 requireProject | `dw-model/.../meta/AccessService.java:304-313` |
| authz 用头的 projectCode 优先 | 同上 `:343-356` |
| saveDomain 无租户过滤、信任 in.id() | `dw-model/.../meta/SpecService.java:58-80` |
| 无 MyBatis 租户拦截器 | `grep TenantLineInnerInterceptor dw-model/...` 零命中 |
| domains 无 tenant_id 列 | `dw-model/.../db/migration/mysql/V1__schema.sql` |
| 全租户项目被推到模块 | `dw-org/.../meta/ProjectService.java` `listAllEntities()` |
| dw-model 未知 code 静默无租户 | `dw-model/.../auth/TenantFilter.java` `resolveTenantId` |
| lineage 无鉴权 | `grep Authorization\|Jwt\|jwt dw-lineage/sql-tools/src/main/java` 零命中；pom 无 security 依赖 |
| lineage 缺头落租户 1 | `dw-lineage/.../tenant/TenantInterceptor.java:58-69` |
| lineage 非数字头建租户 | 同上 `:94-107` |
| RestClient 无超时 | `dw-model/.../internal/OrgClient.java:122-124` |
| 对称密钥默认值相同 | 两个 `application.yml` 的 `dwai.security.jwt-secret` |
| multi 跳过 boot 校验 | `dw-model/.../auth/SecurityConfig.java:103-109` |
| fan-out 无删除对账 | `dw-org/.../internal/ModuleSyncService.java:44-49` |
| 注册表落库并重启载入 | `dw-org/.../internal/ServiceRegistry.java:31-40` |
| implicitTenantId 硬编码 | `dw-org` 与 `dw-model` 的 `DwaiProperties.java` |
