# ADR-0011: dw-lineage 在 multi 模式下复用组织签发的 JWT

## Status

Accepted（后端与前端已落地；壳侧的 token 续期通道待决，见「后果」）

> **2026-09-22 更新**：`standard` 模式的本地账号体系**已实现**，本文档中
> 「standard 未实现 / 与 standalone 完全等价」的结论**已作废**。见下方
> 「后续：standard 模式已落地」一节。multi 复用组织 JWT 的部分未变。

## Context

上一轮多租户评审（`multi-tenant-review.md`）的 **F2** 指出：dw-lineage 在 multi 模式下
**没有任何鉴权** —— pom 里没有 spring-security，全仓搜 `Authorization` / `Jwt` 零命中，
而 `TenantInterceptor` 在请求头缺失时直接回落到默认租户 1。结果是任何能访问端口的人
都能以租户 1 的身份调用约 30 个写端点。技术方案 §3.3 要求的是
「`X-Tenant-Code` 必填 + 组织签发的 JWT」，两条都没落地。

当时的止血只挡住了「完全不带头的意外」：multi 下缺租户头返回 400。但带一个
`X-Tenant-Id: 1` 仍然畅通 —— 这只是把「匿名」变成了「匿名但要声明自己是租户 1」。

改造前先核实了嵌入链路，得到一个关键事实：**壳已经在传 token 了**
（`dw-model/ui` 的 `sessionBoot()` 一直带 `token`），lineage 前端也把它存进了
`sessionStorage`，**只是从未发出去**。所以这次不需要新增协议，缺的是三件事：
发出去、后端校验、以及 token 过期后的续期通道。

## Decision

**multi 模式下复用组织签发的 JWT，不新建账号体系。**

1. **后端**（`com.dwai.lineage.conf.SecurityConfig`）
   - `multi` → `/api/**` 必须携带有效的组织 JWT（`oauth2ResourceServer().jwt()`）
   - `standalone` / `standard` → 全部放行
   - 解码器与 dw-model **同一形态**：`dev` 用 HS256 共享密钥，`oidc` 用 Casdoor 的
     RS256 + JWKS（只持公钥）。属性名与环境变量（`SECURITY_MODE` / `JWT_SECRET` /
     `CASDOOR_ISSUER` / `CASDOOR_JWK`）与另两个服务对齐，一次部署只配一份。
2. **前端**
   - `#boot=` 里的 token 落到 `sessionStorage['sql-tools.bootToken']`
   - axios 请求拦截器注入 `Authorization: Bearer ...`
   - `embed.ts` 已能接收壳推送的 `dw-embed-token` 消息并原地换掉 token（不重载页面）

### 与 dw-model 实现有意不同的四处

| 差异 | dw-model | dw-lineage | 原因 |
|---|---|---|---|
| 模式门槛 | `standalone` 放行，**`standard` 也要认证** | `standalone` 放行，`standard`/`multi` 都要认证 | 曾因 standard 未实现而不同，现已对齐 |
| boot 世代校验 | 有 `JwtSessionEpoch`（`warehouseOnly+multi` 时跳过） | **standard 校验**；multi 跳过 | multi 下 lineage 校验的是**组织**签发的令牌，`boot` 属于组织那次启动；拿本进程世代比对会把所有令牌判为过期 |
| 放行清单 | 显式列出 login/refresh/manifest/health/internal | runtime + auth(config/login/refresh/logout) + actuator + swagger + internal | lineage 的登录端点集更小（无租户选择、无 manifest） |
| 租户上下文挂载 | security filter（`addFilterAfter(BearerTokenAuthenticationFilter)`） | MVC 层 `HandlerInterceptor` | 顺序上 lineage 是「先认证、后解析租户」（正确），但缺 token 且缺租户头时返回 **401 而非 400** |

### 更正：standard 模式的定性

本文档初稿把「lineage 的 standard 模式放行」写成**有意的角色差异**，**这是错的**。
`docs/tech/07-0.2.0.md` 写的是：

- §3.3 Header 表：standard 的 `Authorization` = **本模块 JWT**（不是「无」）；
- §4 鉴权表：`| standard | 本地用户 + 本库「组织角色 + 产品角色」 |`。

也就是说，**standard 带认证是设计要求，数据地图尚未实现**。核实结论：

| 能力 | standalone | standard（当时） | multi |
|---|---|---|---|
| `/api/**` 认证 | 放行 | **放行（未实现）** | 需 JWT |
| 租户头 | 可空回落 | 可空回落 | 必填 → 400 |
| 调组织 | 否 | 否 | 是 |
| `/internal/v1/**` 模块令牌 | 豁免 | 要求 | 要求 |

`isStandard()` 在全部主代码里的使用点是**一处**：`RuntimeController` 把 `standard` 自报给前端。
也就是说 **standard 与 standalone 在行为上完全等价**，这个标签目前没有实际语义。

> 这两段描述的是**补齐之前**的状态。当前形态见本文档末尾「后续：standard 模式已落地」。

对照 dw-model：它的 standard 有真实差异 —— `WarehouseLocalSeedRunner` 在
`isStandard()` 时种入本地账号（`张三/123456`），所以要求认证是成立的。

**风险表述**：把数据地图按 `standard` 独立交付，等于交付一个**没有任何认证**的系统。
现在之所以没出事，是因为「独立交付」走的是 `standalone`（无认证本来就是它设计上的样子）。
**只要这个标签还没实现，就不要对外把它当成「独立版有账号」来承诺。**


## Consequences

### 变简单 / 变安全

- **「匿名访问租户 1」这一形态被关掉**。multi 下没有有效 JWT 连 `/api/**` 都进不来。
- 前端链路完整：`#boot=` → `sessionStorage` → 拦截器，**没有新增协议**，也没有动
  iframe 嵌入方式。
- 三个服务的 `security.*` 配置同形同名，部署时一份配置可覆盖。

### 变难 / 遗留

- **没有关掉租户授权**。组织的 JWT 里**没有租户声明**（只有 `sub` / `username` /
  `name` / `platform_admin` / `boot`），所以它回答「你是谁」而不是「你能碰哪个租户」。
  一个 alpha 租户的合法用户仍可带 beta 租户的 `X-Tenant-Code` 读到 beta 的数据。
  **要关掉它必须再向组织问一次 `authz/check`** —— 这是本 ADR 指向的后续项，
  与 dw-model 的 `requireMember` 多租户加固（ADR-0010）是同一类问题的两侧。
- **`standard` 模式未实现**（详见上面「更正」一节）。
  ~~它目前与 `standalone` 行为完全等价，而设计 §3.3/§4 要求它用「本模块 JWT + 本地用户 + 角色」。
  在它实现之前，不要对外承诺「独立版有账号」。~~
  **→ 已于 2026-09-22 实现，见下一节。**
- **两边都不校验 `aud` / `iss`**，只验签名。dev HS256 下三个服务共用同一个默认密钥，
  因此任一服务签发的令牌其它服务都认（评审 F5）。生产必须走 Casdoor 的 RS256 + JWKS。
- **壳侧的 token 续期通道未接完**。lineage 端（接收 `dw-embed-token` + 携带）已完成，
  但 `ProductEmbed.vue` 只 `import` 了 `AUTH_TOKEN_EVENT`、声明了 `MSG_TOKEN`，
  **既没监听也没 postMessage** —— 目前是死代码。后果：access token（15 分钟）过期后
  仍只能靠 `sessionKey` 变化**重建 iframe**，页面状态会被丢掉。
  **要么补完，要么把这几处死代码删掉** —— 留着一个不生效的协议比没有更糟，
  下一个人会以为它在工作。

---

## 后续：standard 模式已落地（2026-09-22）

上一节的「standard 未实现」是本文档留下的缺口。这部分现在补齐了 ——
按技术方案 §3.3（`Authorization = 本模块 JWT`）与 §4（本地用户 + 本库角色），
给数据地图加了**自己的**账号体系，而不是继续复用组织 JWT。

### 实现范围

| 层次 | 文件 | 说明 |
|---|---|---|
| 迁移 | `db/migration/{h2,mysql,postgresql}/V3__local_auth.sql` | `users` + `refresh_tokens`，三方言各一份 |
| 持久层 | `persistence/LocalUserRow`、`RefreshTokenRow`、`LocalUserRepository`、`JdbcLocalUserRepository` | JdbcTemplate + record，与业务仓储同惯例 |
| 认证 | `auth/LocalTokenEpoch`、`LocalJwtIssuer`、`LocalAuthService`、`LocalAuthController`、`LocalAuthModels` | 登录 / 刷新 / 登出 / me / 改密 |
| 种子 | `auth/LocalAdminSeedRunner` | standard 且零账号时种 `admin/123456` |
| 门禁 | `conf/SecurityConfig` | 加 `PasswordEncoder`；standard 的 `/api/**` 改为 `authenticated()` |

### 三种模式的最终形态

| 能力 | standalone | standard | multi |
|---|---|---|---|
| `/api/**` 认证 | 放行 | **需本模块 JWT** | 需组织 JWT |
| 令牌签发者 | 无 | `LocalJwtIssuer`（HS256） | 组织平台 |
| `boot` 世代校验 | 不适用 | **校验**（重启即全体下线） | **不校验**（`boot` 属组织那次启动） |
| 租户头 | 可空回落 | 可空回落 | 必填 → 400 |
| 调组织 | 否 | 否 | 是 |
| 账号种子 | 无 | `admin/123456` | 无（users 保持空） |

### 关键设计取舍

1. **共用解码器，只在 standard 校验世代。**
   三个服务共用同一组 `JWT_SECRET`，所以「本模块自签」与「组织签发」的令牌是同一算法、
   同一密钥 —— 一个 `JwtDecoder` 就够。但世代校验必须分模式：standard 下 `boot` 是本进程
   这次启动的世代（重启失效是想要的）；multi 下 `boot` 属于组织那次启动，拿本进程的去比
   会**把所有合法令牌判为过期**，等于 multi 彻底登不进去。

2. **users / refresh_tokens 刻意不带 `tenant_id`。**
   登录发生在「知道租户之前」—— 拿用户名查账号时还没有任何租户上下文，加 `tenant_id`
   就只能靠猜。账号是全局的，租户归属由每个请求的 `X-Tenant-Code` 决定。
   这两张表已在 `TenantIsolationArchTest.TENANT_FREE_TABLES` 按表豁免。

3. **刷新令牌只存 SHA-256 哈希，明文只出现一次。**
   库被拖走也拼不回可用令牌。登录时踢掉该账号的旧刷新令牌（「一处登录、别处下线」的
   自救通道）；刷新是滑动续期；登出删库（无状态 JWT 做不到，落库就是为了能撤）。

4. **改密踢掉全部刷新令牌。**
   「怀疑密码泄露」时唯一有效的自救动作就是改密 —— 改完旧 refresh 还能用就等于没改。

5. **不做「强制首登改密」。**
   它需要一条「首次登录」的持久标记，而该标记会变成新的状态机，收益不抵复杂度。
   默认密码 `123456` 只对本地部署成立，对外可访问的部署在 README 里要求先改密。

### 这解决了什么、仍然没解决什么

**解决**：standard 不再是「标着有账号、实际裸奔」的部署。它与 standalone 有了真实语义差异。
单租户语义也从「文档上的约定」落到了代码上：`TenantInterceptor` 对 standard 忽略租户头
（`X-Tenant-Id` / `X-Tenant-Code` 一律不读），请求恒定落在默认租户；`TenantAdminController`
的租户写接口对所有人 403。此前一个 standard 用户带任意 `X-Tenant-Code` 就能读到别的租户，
也能从「设置 › 租户」建出新租户 —— 两个口子都关了。

**仍然没解决**：

- **租户授权**（仅 multi）。本地 JWT 里也没有租户声明（刻意如此，见 §2），所以它同样只回答
  「你是谁」而不是「你能碰哪个租户」。但 standard 下这条**无从触发** —— 租户头根本不参与解析。
  也就是说 standard 是真的单租户，而不只是「约定上」单租户；但**这是拦截器给的，不是这枚 JWT 给的**，
  哪天放开租户头，没有任何一层会拦住跨租户读 —— 所以仍然不要拿它当多租户隔离用。
- **`/api/auth/*` 的会话管理面还很薄**：没有账号管理端点（增删用户只能进库或走 seed）、
  没有 `status` 的启用/停用接口（表里有 `status` 列，但没有改它的 API）。
  需要时再加，当前 `admin` 单账号够本地用。
- **前端仍是「同一套代码、两种取令牌方式」的形态**：multi 用 `#boot=` / `dw-embed-token`，
  standard 需要走 `/api/auth/login`。standard 的登录页尚未实现 —— 见下。

