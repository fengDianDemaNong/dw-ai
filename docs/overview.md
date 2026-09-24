# 优化进展总览

> 更新于 2026-09-22。详细决策见 `tech/adr/`，问题清单见 `tech/optimization-plan.md`、
> `tech/multi-tenant-review.md`。

## 本轮（服务间门禁 + lineage 鉴权 + 嵌壳续期）

| 项 | 状态 | 说明 |
|---|---|---|
| lineage 复用组织 JWT | ✅ | multi 下 `/api/**` 要求组织签发的 JWT；standalone / standard 保持放行。见 ADR-0011 |
| 服务间接口门禁收紧 | ✅ | 未配 `module-token` → 401（此前是**放行**）。三服务同治。见 ADR-0012 |
| 嵌壳 token 续期通道 | ✅ | 原地换令牌，不再重建 iframe；token 退出 `sessionKey`。见 ADR-0013 |
| 续期补齐「子端求、壳端应」 | ✅ | `dw-embed-token-request` 握手 + lineage 401 兜底（embed 重试一次 / 整页回组织）。见 ADR-0013 |
| 三前端构建解阻 | ✅ | `dw-model/ui` 2 处 + `ui` 1 处类型错误已修；三个 UI `vue-tsc` + `vite build` 全绿 |
| `?mode=` 覆盖收紧 | ✅ | 仅 DEV 生效（生产里能把 multi 骗成 standalone） |

### 修掉的真实漏洞

`assertInternal` / `assertModule` 在 `module-token` 为空时**整个方法等价于 `return;`**
（`if (user == null || anonymous) return;` 之后没有代码了）。而三份 yml 的默认值都是空的、
`/internal/v1/**` 在 SecurityConfig 里是 `permitAll` —— **默认部署下「建/删项目镜像」是公开接口**。

回退实验（把修复临时撤掉）复现了它：

```
Status expected:<401> but was:<200>
实际: {"id":3,"tenantId":2,"code":"p_checkout","name":"结算域项目",...}
```

不带任何令牌的 PUT 不仅返回 200，还**真的落库了**。

## 关键数字

| 指标 | 数值 |
|---|---|
| 全量测试 | **445 个用例，0 失败**（dw-org 31 · dw-model 34 · sql-tools 380） |
| 后端测试网起点 | 两个后端 **0 测试** → 30 / 34 |
| lineage 的 multi 鉴权 | 从「零命中」到 SecurityConfig + 前端链路 + 20 条契约用例 |
| 跨服务守卫 | 5 条（不共库 / 不直打兄弟 `/api` / 只传 code / 嵌壳协议两端接上 / 求令牌握手两端接上） |

## 现在的 multi 模式有两层门禁（顺序不能混）

```
身份（组织 JWT）    → 没令牌 401，Security 层
租户上下文（请求头）→ 有令牌但头不对 400，TenantInterceptor
```

`/api/runtime` 是唯一豁免：前端要先知道模式，才知道该不该去取令牌，否则是循环依赖。

## 需要你决定的一件事

**lineage 的租户授权**：组织 JWT 里没有租户声明，所以它只回答「你是谁」。
一个 alpha 租户的合法用户仍可带 beta 的 `X-Tenant-Code` 读到 beta 的数据。
要关掉必须再向组织问一次 `authz/check`（与 dw-model 的 `requireMember` 加固是同一类问题的两侧）。

这一条影响可用性取舍，不宜由我替你定：

- **每次请求都问组织**（严格）：隔离最彻底，但组织不可用时数据地图的读写跟着不可用 ——
  与「模块要能独立活着」的设计目标直接冲突。
- **本地缓存授权结果（TTL）+ 组织不可用时拒绝**：可用性好转，代价是「刚被收回的权限」
  在缓存窗口内仍生效，而且仍需要一个降级策略。
- **只在切换租户时问一次**（写入会话）：最省调用，但事后被移出项目不会被感知。


## 注意

- **多服务组合部署现在必须显式配 `MODULE_TOKEN`**（三处同一个值），否则服务间接口一律 401。
  这是刻意的取舍：用显式失败换掉静默开放。**排查时先看这条**：现象是「组织里项目建得好好的，
  仓建设打开却是『还没有可进入的项目』、数据地图报『租户编码未同步』」，日志里只有一行 401。
  更麻烦的是 multi 下仓建设**不建任何本地租户/项目**，全靠这条链路灌进来 —— 库一空
  （首次部署 / 换库 / 删库）就自己补不回来，把令牌配上重启、让心跳触发全量补发即可恢复。
  三种部署方式（本地 / compose / 安装包）各自配在哪，见根 [README](../README.md)「三种运行模式」。
- ~~**两个前端目前都构建不过**（既有问题，非本轮引入）：`dw-model/ui` 2 个类型错误
  （`App.vue` / `SystemLayout.vue`），`ui` 1 个（`nav.ts`）。
  **这直接卡住了「前端收敛」那项** —— 没有可用的 `build`，连改个 import 路径都无法确认。
  应先修这三处。~~ → **已修**：根因是 `dw-model/ui` 把 `RouteMeta.shell` 收窄成 `'sys'`、
  而布局里仍有从 org 复制来的 `=== 'admin'` 分流（恢复为 `'admin' | 'sys'`）；
  `nav.ts` 是字面量 `Set` 收窄。三个 UI 现在 `vue-tsc --noEmit` 零错误、`vite build` 通过。
- **lineage 的 `standard` 模式是未实现的「空标签」**：它与 `standalone` 行为完全等价
  （`isStandard()` 全仓只有一处使用点 —— 在 `/api/runtime` 里自报），而设计 §3.3/§4 要求
  standard 用「本模块 JWT + 本地用户 + 角色」。**把数据地图按 standard 独立交付 = 交付一个没有认证的系统。**
  独立交付请先走 `standalone`（无认证本来就是它设计上的样子）。
- 两边都不校验 `aud` / `iss`，dev HS256 下三服务共用同一个默认密钥；生产必须走 Casdoor 的
  RS256 + JWKS。与「静态共享密钥不能轮换、无法区分调用方」同源：
  **当前这一层是拓扑信任，一旦网络边界被突破，这些密钥就是万能钥匙。**
- 工作区仍有未提交变更（含本轮的 dw-common 抽取、鉴权、门禁与嵌壳续期）。
