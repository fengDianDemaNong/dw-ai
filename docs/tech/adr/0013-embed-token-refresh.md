# ADR-0013: 嵌壳的 token 续期通道（原地续期，不重建 iframe）

## Status

Accepted

## Context

ADR-0011 之后，数据地图（dw-lineage）在 multi 模式下每个 `/api/**` 请求都要带组织的 JWT，
而 access token 只有 **15 分钟**（`jwt-ttl-seconds: 900`）。

壳（dw-model/ui）一直把 token 放在嵌入 URL 的 `#boot=` 里，而 `ProductEmbed.vue` 的
`sessionKey` **包含 `boot.token`** —— 它的作用是「会话身份变了就重建 iframe」。
于是 token 一续期，`sessionKey` 就变，iframe 被整个重建：**用户正在编辑的 SQL、
浏览中的血缘图、页面状态全部丢掉**。

更糟的是当时的协议只写了一半：

```ts
import { AUTH_TOKEN_EVENT } from '../api/client';   // 导入了
const MSG_TOKEN = 'dw-embed-token';                 // 声明了
// ……但既没有 window.addEventListener(AUTH_TOKEN_EVENT, …)，也没有 postMessage
```

子端（lineage 前端）那一半倒是完整的：`embed.ts` 能收 `dw-embed-token` 并调
`applyAccessToken()` 原地换令牌。**只有发送端是空的。**

这种「看起来已实现的协议」比没有更危险：读代码的人会以为续期是好的，
直到某天有人在嵌入页里编了半小时 SQL 然后被一次 token 续期清空。

## Decision

把通道两端接上，并**让 token 退出 iframe 的会话身份**。

1. **`sessionKey` 去掉 `boot.token`**（`ProductEmbed.vue`）
   会话身份 = `product | origin | tenantCode | projectCode`。
   租户/项目仍在其中 —— 切换租户是真正的会话切换，重建是对的。

2. **壳端推送**（`postToken()`）
   - 触发点一：`AUTH_TOKEN_EVENT`（`client.ts` 的 `setAuthToken()` 会派发，续期与登出都经过它）
   - 触发点二：子应用发来 `dw-embed-ready` 时补推一次。
     这是必要的：token 可能在「iframe 已创建、子应用还没 ready」的窗口里换过一次，
     那次推送没人接。ready 时补一次，保证子应用手里的令牌一定是最新的。
   - **目标 origin 取自 iframe 的 src，不用 `'*'`** —— postMessage 的内容是 token，
     广播出去等于把它交给任何恰好在监听的窗口。
   - 卸载时摘掉监听（否则组件重建后监听器越积越多）。

3. **子端套用**：`embed.ts` 收到 `dw-embed-token` → `applyAccessToken(token, tokenExp)`。
   这一半此前已完成，本次未改。

4. **补上「子端求、壳端应」的第二条腿**（`dw-embed-token-request`）

   只有「壳推、子收」会漏两种情况：壳在「iframe 已创建、子应用还没 ready」的窗口里
   续过一次（那次推送没人接），以及子应用加载时手里的 `#boot=` 令牌已经过期。
   两种都会让子应用停在 401 上 —— 修之前的表现是数据地图整片空白。

   - 子端 `embed.ts` 导出 `requestEmbedToken()`：ready 之后主动要一次 + 401 时再要一次；
     拿不到宿主 origin（referrer 被剥掉）就不发，**不广播 `'*'`**。
   - 壳端 `onMessage` 在 origin 校验之后响应 `MSG_TOKEN_REQUEST` → `postToken()`。
     origin 校验提到了分发之前（此前只对 `dw-embed-ready` 校验）。
   - 子端 `utils/request.ts` 的 401 兜底：**multi 模式**下 embed 态求令牌并原样重试一次
     （`__tokenRetried` 防循环），等不到就给可行动的提示；整页态回组织登录。
     standalone / standard 下不拦 401 —— 那两种模式后端不认证。
   - `EMBED_READY` 同步改成定向发送：拿不到宿主 origin 就不发（宿主有自己的 onLoad 兜底），
     去掉原来的 `?? '*'`。

5. **壳端的 `?mode=` 覆盖收紧为 DEV 仅生效**（两个 UI 的 `getRunMode()`）。
   生产里它能把 multi 部署骗成 standalone：页面按无登录渲染、后端全 401。
   规格 `06-runtime-modes.md` 本来就写着「不要靠前端 `?mode=` 当生产开关」。

6. **加一道跨端源码守卫**（`CrossServiceDesignGuardTest.embedTokenChannelIsWiredOnBothEnds`）
   把上面这些不变量变成可执行断言。见下。

## Consequences

### 变简单 / 变安全

- **token 续期不再重建 iframe**，嵌入页的编辑状态得以保留。
- 推送目标 origin 明确，token 不会被广播。
- 四个不变量进了守卫，两端任一侧将来被改坏都会红：

  | 断言 | 防的是 |
  |---|---|
  | 两端消息类型都是 `dw-embed-token` | 名字对不上 → 推送被静默丢弃 |
  | 壳端 `MSG_TOKEN` 至少被引用一次（不只声明） | **正是这次事故的形态** |
  | 壳端监听有 `add` 也有 `remove` | 监听器泄漏 |
  | 子端确实调用 `applyAccessToken` | 收到却不套用 |
  | `ProductEmbed.vue` 里不出现 `boot.token` | token 被塞回 sessionKey → 原地续期退化成整页重载 |

  守卫的必要性：这条协议横跨两个前端应用，两边都**没有单元测试**，
  任何一端漏了在各自的构建里都是「通过」。

  第二条腿（求令牌）另有一条守卫 `embedTokenRequestChannelIsWiredOnBothEnds`：

  | 断言 | 防的是 |
  |---|---|
  | 两端消息类型都是 `dw-embed-token-request` | 名字对不上 → 求令牌被静默丢弃 |
  | 子端 `EMBED_TOKEN_REQUEST` 至少被引用两次 | 声明了却不发（与首次事故同形） |
  | 壳端 `MSG_TOKEN_REQUEST` 至少被引用两次 | 声明了却不响应 |
  | 两端都不出现 `, '*'` 广播 | 带令牌的握手被交给任意监听窗口 |
  | 子端 401 里同时有 `requestEmbedToken` / `status === 401` / `isMulti()` | 401 直接把错误抛给用户，或把 standalone/standard 用户往组织登录页踢 |

### 验证到什么程度 / 没验证到什么

- **已验证**：`vue-tsc --noEmit` 对改动的两个文件零错误；
  守卫的两条关键断言用**回退实验**确认过会红（摘掉监听 → 报「没有监听 AUTH_TOKEN_EVENT」；
  把 token 塞回 sessionKey → 报「消息通道白做」）。
- **已验证（求令牌那条腿）**：`CrossServiceDesignGuardTest` 5 个用例全绿；
  其中新守卫用回退实验确认有牙齿 —— 临时删掉壳端 `MSG_TOKEN_REQUEST` 分支后，
  测试报「声明了 MSG_TOKEN_REQUEST 却没在消息分发里响应」，恢复后回到全绿。
  三个 UI 的 `vue-tsc --noEmit` + `vite build` 均通过。
- **未验证**：真实浏览器里的端到端行为（需要同时跑起壳、数据地图与组织三个前端）。
  前端目前没有单元测试，Playwright 只覆盖了登录冒烟 —— 这条通道是「源码级守卫 + 类型检查」，不是行为测试。
  求令牌的重试路径（`__tokenRetried` 防循环、2s 等待窗口）也只有类型检查覆盖。

### 遗留

- ~~**两个前端当前都构建不过**（既有问题，非本次引入）~~ → **已修**（2026-09-22）：
  - `dw-model/ui`：`RouteMeta.shell` 在模块 UI 里被收窄成 `'sys'`，而 `App.vue` /
    `SystemLayout.vue` 里仍有 `=== 'admin'` 的布局分流（从 dw-org/ui 复制来）。
    恢复声明为 `'admin' | 'sys'`，与 org 侧一致 —— 本 UI 目前只有 sys 路由，
    运行时行为不变（`ADMIN_HOME` 在本 UI 里本就等于 `SYS_HOME`）。
  - `sql-tools-vue`：`nav.ts` 的 `LOCAL_TENANT_PATHS` 被推断成两个字面量的联合 `Set`，
    `.has(item.path: string)` 报错 → 显式 `new Set<string>([...])`。
  - 三个 UI 现在都是 `vue-tsc --noEmit` 零错误 + `vite build` 通过。
- **登出不清子应用的令牌**：`setAuthToken(null)` 会派发一次空 token 的事件，
  推送过去后子端的 `applyAccessToken('')` 是空操作（`if (token)` 才写），
  子应用会留着一个「服务端已撤销但本地仍有效」的 token 直到过期。
  当前不构成实际窗口 —— multi 下登出会跳组织登录页（`openOrgLogin()`），iframe 随之销毁。
  若将来登出不离开页面，需要补一条「推空 token = 撤销」的语义并改子端的空值处理。
- **整页打开的数据地图仍然没有令牌续期**：`org → lineage` 走 `openLineageApp()`
  （`location.href` + `#boot=`），没有宿主可求令牌。它现在会在 401 时回组织登录页
  （不会静默白屏），但用户手里的路径会丢。要根治需要统一拓扑（都 iframe，
  或都整页 + 回跳重引导），属 `tech/frontend-integration-review.md` 的 P4。
- **消息类型字符串两端各写一份**：前端无测试框架，靠上面两条源码守卫互指。
  抽共享包时一并消除（同 P4）。
