# 待决策与遗留事项

> 2026-09-22 梳理。这一份是「已知但未闭环」的问题清单，按是否阻塞后续工作排序。
> 每条含：现状、影响、可选方案、当前状态、关联材料。决策后请更新状态并归档到对应 ADR 或关闭。

---

## P0 · 阻塞型

### 1. 两个前端构建不过（3 处类型错误，既有问题，非本轮引入）

- **现状**
  - `dw-model/ui`：`App.vue`、`SystemLayout.vue` 各 1 个类型错误。
  - `dw-lineage/ui`：`nav.ts` 1 个类型错误。
- **影响**
  - 两个前端 `build` 直接失败，连「改个 import 路径都无法确认」。
  - 直接卡住「前端收敛」一项——没有可用 `build`，无法验证任何前端改动。
- **建议**：优先修这 3 处，让 `vue-tsc --noEmit && vite build` 跑通。
- **状态**：🔴 待处理（最高优先级）。
- **关联**：`overview.md`「注意」第 2 条。

---

## P1 · 需要人决策

### 2. lineage 的租户授权未闭环

- **现状**
  - multi 模式下，组织 JWT 里**没有租户声明**，它只回答「你是谁」。
  - 一个 alpha 租户的合法用户仍可带 beta 的 `X-Tenant-Code` 读到 beta 的数据。
  - `overview.md` 标记此为「需要你决定的一件事」。
- **影响**
  - 跨租户越权读风险。与 dw-model 的 `requireMember` 加固是同一类问题的两侧。
- **可选方案**（取舍清单）
  1. **每次请求都问组织**（严格）：隔离最彻底，但组织不可用时数据地图的读写跟着不可用——与「模块要能独立活着」的设计目标冲突。
  2. **本地缓存授权结果（TTL）+ 组织不可用时拒绝**：可用性好转，代价是「刚被收回的权限」在缓存窗口内仍生效，仍需降级策略。
  3. **只在切换租户时问一次**（写入会话）：最省调用，但事后被移出项目不会被感知。
- **建议**：默认倾向方案 2（TTL 缓存），在「独立可用」与「隔离」间取平衡；TTL 默认 60s，组织不可用时 fail-closed。
- **状态**：🟡 待决策（影响可用性取舍，不宜由 AI 替你拍板）。
- **关联**：`overview.md`「需要你决定的一件事」；ADR-0011、ADR-0012。

---

## P2 · 部署与安全加固

### 3. multi 多服务部署必须显式配 `MODULE_TOKEN`

- **现状**：三份 yml 的 `module-token` 默认值为空，而服务间接口（`/internal/v1/**`）的门禁已收紧——未配 `module-token` 一律 401（此前是静默放行，等于公开接口）。
- **影响**：多服务组合部署若忘记配置，服务间调用全失败。
- **建议**：部署文档与 `.env.example` 显式列出 `MODULE_TOKEN`（三处同值），并在启动日志里打印一条「未配 module-token，服务间接口已禁用」的提示。
- **状态**：🟡 部署清单**已补**（2026-09-22），启动日志提示**未做**。
  - 已补的落点：根 `README`「三种运行模式」（含不配的**现象**：组织里建了项目、模块里却是
    「还没有可进入的项目」/「租户编码未同步」）、`dw-org`/`dw-model`/`dw-lineage` 三个 README、
    `docs/overview.md`、本页、`.env.example`、`docker-compose.yml`（透传给 org/model 两个 API）、
    三个安装包的 `packaging/conf/env.sh` 填写位。
  - **仍未做**：启动时的那条日志。现在漏配只有等服务间调用打到 401 才暴露，而那个 401 落在
    发起方（组织 fan-out 只 `log.warn`），排查要绕一圈才回到「原来没配令牌」。
- **关联**：ADR-0012。

### 4. dev 下三服务共用 HS256 默认密钥，且不校验 `aud` / `iss`

- **现状**
  - dev HS256 下三服务共用同一个默认密钥（`JWT_SECRET` 默认值）。
  - 两边都不校验 `aud` / `iss`。
- **影响**
  - 当前这一层是**拓扑信任**：一旦网络边界被突破，这些密钥就是万能钥匙；静态共享密钥无法轮换、无法区分调用方。
- **建议**：生产必须走 Casdoor 的 RS256 + JWKS；`JWT_SECRET` 默认值仅在 dev 生效，生产启动校验必须拒绝默认值。
- **状态**：🟡 待加固（生产上线前必做）。
- **关联**：`overview.md`「注意」最后一条；ADR-0011。

### 5. 模块侧的租户许可曾与组织脱钩（已修，2026-09-22）

- **症状**：组织里只开通了仓建设的租户（`tenant01`），在仓建设里照样看得见「数据地图」菜单，
  点进去必然报错；反过来只开通数据地图的租户会被凭空赋予仓建设。
- **根因**：组织把项目镜像推给模块时**不带许可** —— `ModuleSyncService.put` 的 body 只有
  `name/code/tenantCode/tenantName/id`。模块侧于是只能自己编：`ProjectService.ensureTenant`
  在**首次见到该租户**时硬编码一条 `[warehouse, metadata]`。许可的权威在组织，模块却自己造。
  （与 ADR-0012 的 `MODULE_TOKEN` 是两回事：那个管「能不能调」，这个管「调过来的内容对不对」。）
- **改法**（三处，2026-09-22）：
  - **组织侧**：fan-out body 带上 `modules` / `aiCaps`；改租户许可后**主动重推该租户的项目**
    （`PlatformService.patchTenant` → `resyncTenant`）—— 否则要等下次有人动项目才生效。
  - **模块侧**：`ensureTenant` 用组织给的值校准本地行。**null（组织没带）与空数组（确实没开通）
    必须分开**，前者不动本地。新建租户的回落从 `[warehouse, metadata]` 收窄为 `[warehouse]`。
  - **前端**：`nav.ts` 的数据地图组、`router/index.ts` 的 8 条 lineage 路由补 `module: 'metadata'`。
    菜单过滤挡不住地址栏直达，两层都要。
- **生效条件**：需重启仓建设。存量错行由「组织全量补发」自动纠正（心跳 catchUp，≤120 秒），
  或在组织里编辑一次该租户立即触发。
- **遗留**：许可仍只搭「项目镜像」这一条通道。组织里建了租户却**没有任何项目**时，
  模块侧不会知道这个租户存在 —— 既有行为，非本次引入。

### 10. 服务注册的心跳无条件覆盖手工登记地址，跨机部署时回连必失败（2026-09-23 诊断）

- **现象**：组织平台「服务注册」里手工登记模块地址为 `192.168.12.136`，30 秒内被改回 `127.0.0.1`。
- **根因**：手工登记（`PlatformController:113`）与模块心跳（`InternalController:89`）走的是
  **同一个 `registry.put`**，表里没有「来源」列，心跳无条件覆盖 `baseUrl`（`ServiceRegistry:18`
  的注释原文就是「心跳覆盖写，不记流水」）。心跳周期 30 秒（`LineageHeartbeat:21` /
  `WarehouseHeartbeat:21`）。而模块自报的是**自己配置里的值**、不探测真实网卡：
  lineage 走 `application-multi.yml:8` 的默认 `http://127.0.0.1:18082`（安装包 `conf/env.sh`
  里根本没有 `SERVICE_BASE_URL` 这一项），model 走 `application.yml:40` 的
  `http://127.0.0.1:18081`（`packaging/conf/env.sh:20` 硬编码）。
- **影响**：`baseUrl` 是**组织回连模块的地址**（`ModuleSyncService:63` 拿它当 RestClient 的
  baseUrl 去推项目镜像）。分机部署时 `127.0.0.1` 指向组织自己那台机器，推送失败被吞成一条
  `log.warn`（`:78-80`），**接口层无报错、注册表状态列照样是绿的** —— 因为心跳是模块主动发出的
  （模块→组织通），坏的是反向回连（组织→模块）。表现与 P2-3 相同（「租户编码未同步」），排查时两条都要看。
  次生：`catchUp` 含「baseUrl 变了」这一条（`InternalController:86-88`），页面改地址会来回触发全量推。
- **可选方案**：① 运维约定 —— 起模块时显式设 `SERVICE_BASE_URL` 为对外可达地址，配套补两处
  `env.sh` 的填写位（不改代码，立刻可用）；② 防呆 —— multi 下启动时若仍是回环地址就打 `warn`，
  可与 P3-9（`org-base-url` 默认值隐患）一并做；③ 改语义 —— 加一列记来源，手工登记的产品心跳
  只更新 `seenAt`。注意 `ServiceRegistry` / `ModuleSyncService` **目前零测试覆盖**，改前先补网。
- **状态**：🟡 已记录、待排期（2026-09-23 用户决定「只出诊断报告，先不动」）。
- **关联**：完整诊断见 [`service-registry-address-overwrite.md`](service-registry-address-overwrite.md)；
  P2-3（症状相同根因不同）、P3-9（同类默认值隐患）。

---

## P3 · 收尾与对齐

### 6. 工作区有未提交变更

- **现状**：工作区含本轮及此前的未提交变更（dw-common 抽取、鉴权、门禁、嵌壳续期、本次 5173 端口变更与两份新文档）。
- **建议**：按主题分批提交（端口变更 / 文档 / 鉴权加固 分开），便于回溯。
- **状态**：🟡 待提交。

### 7. 原型端口（5175）与实服务端口（5173）不一致

- **现状**
  - 实服务 `dw-lineage` 前端已统一为 5173（本次变更）。
  - `docs/product/versions/0.2.0/` 下的交互原型仍用 5175 作为「数据地图原型」端口（`run-proto.mjs`、`prototype/`、`org/`、`spec/`、`README`、`RELEASE`、`PRD`、`docs/tech/07-0.2.0.md`）。
- **影响**：不阻塞——原型（`npm run proto`）与 dev 服务不同时占用，端口是两套独立约定。但术语重叠可能造成混淆。
- **建议**：0.2.0 落地实现时统一对齐到 5173；在此之前原型端口保持现状（属冻结的设计版快照）。
- **状态**：🟢 已记录，待 0.2.0 落地统一。

### 8. lineage `standard` 前端登录页尚未实现

- **现状**：后端 LocalAuth API 齐备（`/api/auth/config`、`/login`、`/refresh`、`/logout`、`/me`、`/profile`、`/password`），但前端 `ui/src` 暂无对应登录页与 token 注入点；multi 的令牌链路在前端侧仍需核对。
- **建议**：补 standard 登录页 + 401 刷新兜底；multi 的 `ProductEmbed` token 转发（死代码）与 `map/embed.vue` 对 token 非响应式问题一并修（见 `frontend-integration-review.md` R1/R2）。
- **状态**：🟡 待实现。
- **关联**：ADR-0011「后续」；`frontend-integration-review.md`。

### 9. `RunModeSmokeTest` 有一条依赖「本机有没有跑组织」

- **现状**：`RunModeSmokeTest$Multi#refreshIsForwardedToOrgAndFailsLoudlyWhenOrgIsAbsent`
  断言 503，前提是「`dwai.org-base-url` 没配」。可 `application.yml:36` 给了非空默认值
  `${ORG_BASE_URL:http://127.0.0.1:18080}` —— 本机一旦跑着组织平台，请求就真发出去、
  拿到 401，断言变红（2026-09-22 实测：`{"error":"登录已过期","status":401}`，
  日志里还有心跳的 `向组织平台登记失败: 401`）。
  也就是说**这条测试的结果取决于开发机上有没有起 org**，跟代码对不对无关。
- **影响**：回归网会莫名其妙红一条，久了就没人信它 —— 真失败反而被当成「那条老毛病」。
- **两条路**（都有取舍，未擅自改）：
  1. 给该 `@Nested` 加 `properties = "dwai.org-base-url="`，恢复测试原意（最小改动）；
  2. 把 `application.yml` 的默认值改成空 —— 但这个默认值本身就是隐患：生产若忘配
     `ORG_BASE_URL`，会静默去连 `127.0.0.1:18080`。改之前得先想清楚开发体验怎么补。
- **状态**：🟡 待定。

---

## 关键数字（参考）

| 指标 | 数值 |
|---|---|
| 后端全量测试 | 533 个用例（dw-org 31 · dw-model 48 · dw-lineage 454），2026-09-22 复测。dw-model 有 1 条环境依赖的红，见 P3-9 |
| 后端测试网起点 | 两个后端 0 测试 → 30 / 34 |
| lineage 的 multi 鉴权 | 从「零命中」到 SecurityConfig + 前端链路 + 20 条契约用例 |
| 跨服务守卫 | 4 条（不共库 / 不直打兄弟 `/api` / 只传 code / 嵌壳协议两端接上） |
