# 跨服务上下文（租户 / 项目 / 用户）

产品版本 0.2.0 · 文档修订 1.0 · 状态：设计中  
实现窗口按本篇统一改，禁止各产品再各写一套头、一套 boot、一套本地数字 id 对外。

## 1. 只认组织侧稳定编码

组织平台是人、租户、项目的真源。跨进程**只传编码，不传各库自增数字 id**。

| 字段 | 含义 | 例 | 不是 |
|---|---|---|---|
| `tenant_code` | 租户全局唯一编码 | `xinghe` `qihang` | `1`、`t-xinghe` |
| `project_code` | 租户内唯一项目编码 | `trade_dw` `default` | `1`、`p-empty` |
| `user_id` | 组织用户 id | `u-li` | 各产品本地账号表主键 |

各产品库可以有自己的 `tenant.id` / `project.id`（自增）。那是**库内主键**，出进程一律先解析成 code，再发给别人。接到 code 后在本库按 code 找到或补齐本地行（`PUT /internal/v1/projects/{project_code}`）。

数据对象另认 `catalog.schema.table`，与租户/项目正交。

## 2. HTTP 头（模块 API）

所有产品进程的业务 API（`/api/**`）统一读：

| 头 | 必填 | 独立 | 普通 | 多租户 |
|---|---|---|---|---|
| `X-Tenant-Code` | multi 必填 | 可空，只展示 | 不传（无租户） | 必填，对不上 400 |
| `X-Project-Code` | multi 必填 | 可空，只展示 | 可空，落到本模块默认项目 | 必填，对不上 400 |
| `X-User-Id` | 写操作在普通/多租户必填 | 可空 | 本模块用户 | 组织用户 |

禁止：

- 用 `X-Tenant-Id` / `X-Project-Id` 传数字本地 id 当跨服务契约（本库内部可以继续用）。
- code 对不上时静默落到默认租户 `1`（会把启航的数据写进别人的库）。
- 浏览器直打兄弟产品的 `/api`；只打当前打开的那个产品，上下文由壳注入。

过渡期（本原型已如此）：`X-Tenant-Id` / `X-Project-Id` **若是非数字，按 code 解析**；实现窗口改完后只留 `X-*-Code`。

## 3. 浏览器打开另一个产品

壳跳到独立产品（含 iframe）只带 hash，不把组织内部主键冒充 code：

```
#boot={"userId","tenantCode","projectCode","tenantName","projectName","embed"}
```

| 字段 | 用途 |
|---|---|
| `userId` | 组织用户，给对方鉴权/展示 |
| `tenantCode` / `projectCode` | 对方请求头与落户用的编码 |
| `tenantName` / `projectName` | 展示；可空 |
| `embed` | `true` 时对方去掉自己的导航壳 |

对方启动时：按 code 落户（没有就 `upsert`），再发业务请求。不要把 `t-xinghe` / `p-trade` 这种组织库主键塞进 `tenantCode`。

组织平台打开仓建设仍可用组织自己的会话 id（`userId` + 组织 `tenantId` + `projectId`），那是**组织 ↔ 仓建设壳**，不是产品之间的契约。仓建设再去开数据地图时，必须转成 code。

## 4. 组织 ↔ 产品 REST

创建/停用项目时组织调用已登记产品：

```
PUT    /internal/v1/projects/{project_code}
DELETE /internal/v1/projects/{project_code}
```

Body / 头带 `tenantCode`（以及名称）。产品按 code 落一份镜像，本地数字 id 自己管。模块心跳、鉴权仍走 [06-runtime-modes.md](./06-runtime-modes.md) 的 `/internal/v1/registry/*` 与 `/internal/v1/authz/check`；`authz` 的入参同样用 code，不用各库数字 id。

## 5. 实现窗口对照（改的时候用）

| 现在容易错的 | 改成 |
|---|---|
| sql-lineage `X-Tenant-Id: 1` 与组织 `t-qihang` 混用 | 对外只出 `qihang` / `default` |
| boot 里 `tenant` / `tenantId` / `tenantCode` 三种名字 | 跨产品只留 `tenantCode` / `projectCode` |
| 头对不上回落到租户 1 | 400，并提示编码未同步 |
| 仓建设 iframe 把组织主键当 code | 必须用 `tenant.code` / `project.code` |
