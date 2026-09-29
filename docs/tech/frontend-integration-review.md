# 前端整合评审：三模式下的服务间整合（multi 为重点）

> 评审日期：2026-09-22 · 范围：三个 UI（dw-org/ui 壳、dw-model/ui 仓建设、dw-lineage/sql-tools-vue 数据地图）
> 的跨服务整合：`#boot=` 引导协议、token 通道、路由守卫、请求头注入、跨应用导航。
> 证据等级：**[读证]** = 源码调用链确认；**[推断]** = 结构推出、未复现。
> 配合阅读：`multi-tenant-review.md`（后端侧）、ADR-0011（lineage 复用组织 JWT）、ADR-0012（module-token 门禁）。

---

## 结论

**standalone / standard 两种模式前端自洽，没有整合问题。multi 模式的整合骨架
（`#boot=` 引导、路由守卫分流、`X-Tenant-Code` / `Authorization` 头注入）是通的，
与后端两层门禁的契约对得上。但 token 生命周期这条线只建了一半，
access token（默认 15 分钟）到期后，被整合进来的服务会整片失活且无兜底。**

> **本轮已修**：P1 / P2 / P5 与 P6 的安全小项已落地（见下方「修复状态」）。
> 原文保留，作为问题被发现时的证据记录。

一句话概括当前状态：

> 引导协议（一次性传 token）做完了，续期协议（持续喂 token）接收端做完了、
> 发送端没接上，整页打开的场景连接收端都没有。

---

## 修复状态（2026-09-22 更新）

> 三个 UI 均通过 `vue-tsc --noEmit` + `vite build`；dw-org 全量测试 31 个用例 0 失败。
> 实施细节见 [ADR-0013](adr/0013-embed-token-refresh.md)。

| 问题 | 状态 | 落地方式 |
|---|---|---|
| P1 iframe 内 token 不续期 | ✅ 已修 | `ProductEmbed` 监听 `AUTH_TOKEN_EVENT` 定向 postMessage `dw-embed-token`；子应用 ready 时补推一次（ADR-0013） |
| P2 lineage 401 无兜底 | ✅ 已修 | 新增 `dw-embed-token-request` 握手：embed 态求 token 并重试一次（防循环）；整页态回 org 登录；启动时（`embed=1`）主动要一次 |
| R3 sessionKey 含 token 导致重建丢状态 | ✅ 已修 | `sessionKey` 去掉 token 维度，token 变化改走消息通道 |
| P5 `?mode=` 可覆盖运行模式 | ✅ 已修 | 两个 UI 的 `getRunMode()` 中 `?mode=` 仅 `import.meta.env.DEV` 生效（与规格 `06-runtime-modes.md` 一致） |
| P6 定向发送 / 静默丢弃 | ✅ 部分 | `EMBED_READY` 不再广播 `'*'`（拿不到宿主 origin 就不发，宿主有 onLoad 兜底）；`asciiHeader`（dw-model/ui 与 dw-org/ui）丢弃非 ASCII 时 `console.warn` |
| 守卫 | ✅ 新增 | `CrossServiceDesignGuardTest.embedTokenRequestChannelIsWiredOnBothEnds`（5 条断言），回退实验确认有牙齿；守卫总数 4 → 5 |
| P3 refreshToken 跨 origin 复制 + 轮换 | ⏳ 未动 | 需要协议设计（bootstrap 码换 refresh，或模块不持 refresh），属决策项 |
| P4 拓扑统一 / 运行期地址发现 | ⏳ 未动 | 需与 05-0.1.5-modes「拆控制台」合着做，属决策项 |
| P6 boot 协议三处复制 | ⏳ 未动 | 抽共享包是结构改动，建议随 P4 一起做 |
| 顺带修掉的存量问题 | — | `dw-model/ui` 的 `RouteMeta.shell` 收窄成 `'sys'` 与布局里的 admin 分支冲突（2 处 `vue-tsc` 报错，阻碍 `npm run build`）→ 恢复为 `'admin' \| 'sys'`；`sql-tools-vue` 的 `nav.ts` 字面量 `Set` 类型报错 → `Set<string>` |

**遗留风险（已修部分）**：
- 消息类型字符串（`dw-embed-token` / `dw-embed-token-request`）在宿主与子应用两侧各写一份，
  前端无测试框架，靠两条源码守卫互指。抽共享包时一并消除。
- 求令牌的重试路径（`__tokenRetried` 防循环、2s 等待窗口）只有类型检查覆盖，**无行为测试**；
  端到端需要同时跑起壳、数据地图与组织三个前端。

---

## 修复状态（2026-09-24 更新：门户集成）

> 本轮做的是「组织当壳、把被嵌服务配进菜单」这件事本身（含项目同步由 push 改 pull）。
> 路由与协议真源见 `06-0.1.5-routes.md` §7 / §12，此处只记与本文问题清单的对应关系。

| 问题 | 状态 | 落地方式 |
|---|---|---|
| P4 拓扑统一 / 运行期地址发现 | 🟡 大半已修 | 组织侧栏不再硬编码：菜单来自 `nav_items` 表（`/api/nav`，消费面不要求平台管理员），产品地址来自服务登记的 `frontend_url`（**页面**地址，不再需要后端地址）。org 现在**能嵌 lineage**（搬来 `ProductEmbed`，`/org/embed/:product/**`）；model 仍走整页跳转（它没有子端接收端） |
| P4 的剩余部分 | ⏳ 未动 | 三个 UI 各有一份 `ProductEmbed` / `embed.ts` 副本，未抽共享包；`service_registry.base_url` 三列保留但无写入源 |
| R2 白名单依赖 `document.referrer` | ✅ 已修 | boot 载荷新增 `hostOrigin`（宿主自报 origin），子端白名单 = `hostOrigin` ∪ 运行期推导的组织平台地址 ∪ referrer。**实测**：宿主 origin 配错且带 `referrerpolicy="no-referrer"` 时，有 `hostOrigin` 才收得到 token。（2026-09-25：两个 `VITE_*_ORIGIN` 环境变量已删除，白名单只剩这三项） |
| R1 打包态 iframe 白屏 | ✅ 已修 | lineage `SecurityConfig` 把 Spring Security 默认的 `X-Frame-Options: DENY` 换成 CSP `frame-ancestors`（源用 `cors.allowed-origins`）；**表达不出来的白名单一律回落 DENY**（fail-closed，见 `EmbedFramePolicyUnsetTest`） |
| P6 boot 协议三处复制 | ⏳ 变成四处 | 新增 org 副本后，守卫 `CrossServiceDesignGuardTest` 里那两条「两端都接了消息通道」的断言**参数化**成扫描所有 `*/ui/src/components/ProductEmbed.vue` × 所有 `*/ui/src/config/embed.ts`，否则新副本游离在守卫之外 |
| P6 消息类型字符串重复 | ⏳ 未动 | 同上，抽共享包时一并消除 |

**本轮已知缺口**：**产品嵌入**的 iframe（`ProductEmbed`）未加 `sandbox`（有意保留，见 §12 与 P6 那一行）；组织删项目后模块侧留孤儿镜像（`dw-lineage/docs/KNOWN_ISSUES.md` 第 9 条）。V29 新增的**外链菜单** iframe 是另一个面（目标站不可信），已加 `sandbox` + `referrerpolicy="no-referrer"`。

**入口页的 Tab（2026-09-27）是同源 iframe，做法与前两者相反**：Tab 里嵌的是 org 自己那一页（`pages/entry.vue`），**不加 `sandbox`** —— 不给 `allow-same-origin` 会让它成 opaque origin、sessionStorage 隔离，框里的 org 读不到 `dw-ai.token` 而整页 401；给了 `allow-same-origin` 则对同源内容形同虚设。同一个原因让 org 的 `isEmbed()` **不能**照抄 model/lineage 的 sessionStorage 标记（同源共享存储，子帧写的标记会被父窗口 F5 读到，表现为外壳侧栏消失），只能用 `window.self !== window.top` + 同源父窗口判定；`expireIdle()` / `leaveTenant()` 也各加了 embed 熔断，防子帧清掉父页共用的令牌与租户。

---

## 一、没问题的部分（先确认哪些别动）

| # | 环节 | 现状 |
|---|---|---|
| OK1 | 三模式守卫分流 | `dw-model/ui/router/index.ts`：standalone 免登直达、standard 落本地 `/login`、multi `openOrgLogin()`，分派清晰 [读证] |
| OK2 | `#boot=` 消费 | 三个 UI 的 `consumeBootHash()` 解析后 `history.replaceState` 清掉 URL，token 不会一直挂在地址栏 [读证] |
| OK3 | 头注入契约 | warehouse 侧 `doFetch` 发 `Authorization` + `X-Tenant-Code/X-Project-Code`（ASCII 校验），与后端 `TenantInterceptor`「缺头 400」的契约一致 [读证] |
| OK4 | lineage 落户 | `ensureOrgProject()` 走 `/internal/v1/projects/{code}` PUT，绕开「头是 code、库里没有」的死锁，与 ADR-0011 配合正确 [读证] |
| OK5 | multi 下 warehouse 续期 | dw-model 的 `/api/auth/refresh` 在 multi 分支代理到 org（`OrgClient.refresh`），宿主自己能续 [读证] |

---

## 二、问题清单（按严重度）

### 🔴 P1（严重）iframe 里的数据地图在 multi 下活不过一个 token 周期

三个事实叠加〔[读证]〕：

1. **发送端是死代码**：`ProductEmbed.vue` import 了 `AUTH_TOKEN_EVENT`、声明了
   `MSG_TOKEN = 'dw-embed-token'`，但**既没监听也没 postMessage**（overview.md 决策点 1 已列）。
2. **就算想靠重建 iframe 也不成立**：`map/embed.vue` 的 `src` 是 computed，
   token 在 `lineageEmbedUrl()` 内部从 sessionStorage 读——**不是响应式依赖**。
   token 续期后 computed 不重算 → iframe 既收不到新 token，也不会按 sessionKey（含 token）触发重建。
3. **接收端是通的**：lineage `embed.ts` 的 `EMBED_TOKEN` 监听 → `applyAccessToken()` → 更新请求头，
   这半边已完成 [读证]。

后果：multi 下打开数据地图，15 分钟后 iframe 内所有请求 401；
`utils/request.ts` **没有任何 401 兜底**，表现为页面静默变空 / 报错，用户只能整页刷新。

**修法（小）**：`ProductEmbed` 补上转发——

```ts
onMounted(() => {
  window.addEventListener(AUTH_TOKEN_EVENT, forwardToken);
  // forwardToken: frame.contentWindow.postMessage(
  //   { type: MSG_TOKEN, token, tokenExp }, new URL(frameSrc.value).origin)
});
```

十几行。**不要选「删掉死代码」这个选项**——删了 P1 依旧成立，只是少了半成品协议。

### 🔴 P2（严重）org → lineage 是整页跳转，续期通道完全不存在

- `AppSidebar.vue` / `projects.vue` 用 `openLineageApp()`（`location.href` + `#boot=`）打开数据地图 [读证]。
- 顶层打开的 lineage **没有宿主**（iframe 消息协议用不上）、**没有 refresh 逻辑**
  （`bootRefresh` 存进 sessionStorage 后从未被读取）、**没有 401 处理**。
- org 自己会续期，但新 token 不会同步给已打开的 lineage 标签页（不同 origin、不同
  sessionStorage，sessionStorage 本身也不跨标签页）。

后果与 P1 相同，且这次连「iframe 重建」的退路都没有。
**注意**：dw-org/ui 里还留着一份没人调用的 `lineageEmbedUrl()` [读证]——
说明「org 嵌 lineage 走 iframe」曾计划过后来放弃，这正是 P4 说的拓扑漂移痕迹。

**修法**：lineage `request.ts` 增加 401 兜底：embed 态向宿主 postMessage 请求新 token
（或宿主检测 401 后重建 iframe）；整页态带当前路径回跳 org 重新走一遍 `#boot=`。

### 🟠 P3（高）refreshToken 被复制到多个 origin，而 org 的 refresh 是 rotate 语义

- org 侧 `RefreshTokenService.rotate()`：每次刷新**轮换** refresh token [读证]。
- `openWarehouseApp/openLineageApp` 把 `token + refreshToken` 经 `#boot=` 复制到另一个
  origin 的 sessionStorage。两份副本**各自独立 refresh**：谁先 rotate，另一份就作废。

典型触发〔[推断]〕：用户从 org 中键新标签打开 warehouse，两个标签都活着，
下一个续期周期必有一侧 refresh 失败 → 随机被登出。
浏览器「后退」回 org（bfcache）后继续操作，同样会踩到。

**修法方向（需要设计，建议立项）**：跨 origin 只传 access token + 一次性 bootstrap 码，
由模块侧拿码向 org 换自己的 refresh token；或模块侧不持有 refresh，401 时整页回 org 重引导。
当前「复制 refresh + 轮换」的组合，多标签场景下行为不确定。

### 🟠 P4（高）整合拓扑一半 iframe、一半整页跳转；origin 全是构建期常量

| 路径 | 方式 | 后果 |
|---|---|---|
| org → warehouse | 整页跳转（`openWarehouseApp`） | 丢 org 导航状态；回退后 org 的 refresh 可能已被 warehouse 侧 rotate 掉（P3） |
| warehouse → lineage | iframe（`ProductEmbed`） | 唯一有消息协议的链路，但协议半成品（P1） |
| org → lineage | 整页跳转（`openLineageApp`） | 无任何续期通道（P2） |

- `orgOrigin()/warehouseOrigin()/lineageOrigin()` 默认 `127.0.0.1:517x`，靠 `VITE_*`
  **构建期**注入；而模式是**运行期** `/api/runtime` 决定的。运行期拓扑 + 构建期地址 = 部署耦合。
- 后端已有服务注册表（heartbeat 带 baseUrl）[读证]，前端却不用它。
  生产漏配一个 `VITE_`，跳转静默指向 localhost，没有任何校验或提示。

**建议**：统一拓扑（要么都 iframe + 消息协议，要么都整页 + 回跳重引导）；
模块入口地址改从 org 的 registry 拿，或至少启动时探活校验。

### 🟡 P5（中）`?mode=` 查询参数可覆盖运行模式

两个 UI 的 `getRunMode()` 都让 URL 的 `?mode=` 优先并**写入 sessionStorage** [读证]。
multi 部署下用户带一个 `?mode=standalone` 链接进来：守卫按 standalone 放行、跳过 idle 过期、
页面按无登录渲染，后端全 401——「页面在、数据全空、原因难查」。
后端守得住，但前端模式不该可被 query 覆盖。建议仅 `import.meta.env.DEV` 允许。

### 🟡 P6（低）若干小项

| 项 | 说明 |
|---|---|
| origin 白名单形同虚设 | lineage `embed.ts` 的 `allowed()` 用 `document.referrer` 兜底 = 「谁嵌我信谁」；拿不到 referrer 时 `EMBED_READY` 直接 `postMessage('*')` [读证] |
| boot 协议三处复制 | `EmbedBoot/consumeBootHash/lineageEmbedUrl` 在三个 UI 各一份，已漂移（org 的带 `embed:true`，warehouse 的多 `tenantName/projectName`，org 的 `lineageEmbedUrl` 是死代码）。应进共享包一处定义 |
| `asciiHeader` 静默丢弃 | 非 ASCII 头直接吞掉无日志；若 userId 之类来源被污染，排查困难 |
| iframe 无 `sandbox/allow` | **产品嵌入**的 iframe（`ProductEmbed`）仍然没有：它跑的是自己人写的子应用，消息面里没有敏感负载，收紧它会连带卡住当前的消息协议与 token 转发（P1），**是有意的取舍，不是漏了**。**外链菜单**（V29，`pages/external.vue`）是另一回事 —— 目标站不可信，已加 `sandbox="allow-scripts allow-forms allow-same-origin allow-popups"` + `referrerpolicy="no-referrer"`（地址里带 token，不能再经 Referer 泄给第三方资源）；刻意**不给** `allow-popups-to-escape-sandbox` 与 `allow-top-navigation` |

---

## 三、建议（按取舍排序）

| # | 动作 | 收益 | 代价 |
|---|---|---|---|
| R1 | `ProductEmbed` 补 `AUTH_TOKEN_EVENT → postMessage(MSG_TOKEN)` 转发 | 关闭 P1，iframe 内数据地图不再 15 分钟必死 | 十几行，低风险 |
| R2 | lineage `request.ts` 加 401 兜底（embed 通知宿主 / 整页回跳 org） | 关闭 P2，同时是 R1 的失败兜底 | 中；需区分 embed / 整页两种态 |
| R3 | `map/embed.vue` 的 src 对 token 变更显式响应（或在 R1 落地后删除 sessionKey 里的 token 依赖） | 消除「重建丢状态」与「不重建拿不到新 token」的两难 | 小 |
| R4 | refresh 跨 origin 方案立项（bootstrap 码换 refresh，或模块不持 refresh） | 关闭 P3 的随机登出 | 高；协议变更，需设计文档 |
| R5 | 统一整合拓扑 + 模块地址改运行期发现（registry） | 关闭 P4；消除构建期耦合 | 中；与 05-0.1.5-modes 的「拆控制台」步骤合着做 |
| R6 | `?mode=` 覆盖仅限 DEV；boot 协议抽进共享包 | 关闭 P5/P6 的一半 | 小 |

R1 + R2 建议本轮做（都是 multi 上线前的硬前提）；R4、R5 需要决策，建议各出一份小设计。

---

## 附：证据索引

| 结论 | 位置 |
|---|---|
| ProductEmbed 未监听/未发送 token | `dw-model/ui/src/components/ProductEmbed.vue`（全文无 AUTH_TOKEN_EVENT 监听、无 MSG_TOKEN postMessage） |
| src computed 不含 token 依赖 | `dw-model/ui/src/pages/map/embed.vue:18-30` |
| lineage 接收端完成 | `dw-lineage/sql-tools-vue/src/config/embed.ts:29-34`、`stores/tenant.ts:100-103` |
| lineage 无 refresh / 无 401 处理 | `grep bootRefresh` 仅 runtime.ts 存储一处；`utils/request.ts` 无 401 分支 |
| org → lineage 整页跳转 | `dw-org/ui/src/components/AppSidebar.vue:51`、`pages/projects.vue:283` |
| org 的 lineageEmbedUrl 死代码 | `dw-org/ui/src/config/product.ts:44-59`（无调用方） |
| refresh 轮换 | `dw-org/api/.../auth/RefreshTokenService.java:51` `rotate(String raw)` |
| multi 下 warehouse refresh 代理 org | `dw-model/api/.../auth/AuthController.java:65-73` |
| `?mode=` 覆盖并持久化 | `dw-model/ui/src/config/runtime.ts:17-26`、`dw-lineage/sql-tools-vue/src/config/runtime.ts:10-18` |
| origin 构建期默认 localhost | 三处 `config/product.ts` / `runtime.ts` 的 `VITE_*_ORIGIN` 默认值 |
| referrer 兜底 + `'*'` 广播 | `dw-lineage/sql-tools-vue/src/config/embed.ts:10-24,44-45` |
