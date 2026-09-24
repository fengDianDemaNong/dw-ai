# 运行模式、进程拆分与权限

产品版本 0.1.5 · 文档修订 1.0 · 状态：设计中  
对齐现有代码：`ui/`、`services/api`、`packages/engine`、`services/rules`。本版先定契约，再拆实现。

每个**模块进程**（仓建设、元数据、质量、数据服务、组织平台）启动时选定一种模式，运行中不能改。三种模式是同一套业务代码上的开关，不是三份产品。

## 1. 三种模式

| 模式 | 配置 | 用户 | 租户 | 谁管身份 | 典型样子 |
|---|---|---|---|---|---|
| **独立** `standalone` | 只跑本模块 | 无 | 无 | 没有。请求里若带了人/租户/项目，只当展示标签 | 现在的 sql-lineage：能看见「当前项目」字样，但不实现登录、用户表、权限 |
| **普通** `standard` | 本模块自带轻量账号 | 有 | 无 | 本模块本地用户表。没有平台后台、选租户、授权码 | 单企业自用仓建设 |
| **多租户** `multi` | 必须先有组织平台 | 有 | 有 | 组织平台是人/租户/项目的唯一真源。本模块不建用户 | 套件组合 |

独立 ≠ 普通。独立是「这个服务不管人」；普通是「这个服务自己管人，但不管租户」。

组织平台本身：

- 不做 `standalone`（它的业务就是人）。
- `standard`：只有用户和项目，没有租户字样，本进程即身份真源。
- `multi`：完整租户 / 平台用户 / 授权码 / 服务注册。其它模块以 `multi` 启动并登记进来。

## 2. 进程与仓库（相对现在）

现在是一套控制台 + 一套 Java API，租户和建模挤在 `services/api` + `ui`。目标拆开：

| 进程 | 现在 | 目标 |
|---|---|---|
| 组织平台 API + 控制台 | 混在 `services/api` 的 `auth` / `Tenant*` / `Platform*`，`ui` 的 `/admin` `/sys` | 独立项目，单独启动。原型已拆：`docs/product/versions/0.1.5/org` → 4224 |
| 仓建设 API + 控制台 | 同一套 API 里的 spec/table，`ui` 的 `/w` | 独立项目。原型：`prototype` → 4223。`multi` 时不提供登录页，跳组织平台 |
| 元数据 | 外部 sql-lineage（独立模式） | 三种模式都要能跑。`standalone` 保持现状 |
| 规则引擎 | `services/rules` | 继续无用户，仓建设 API 用 REST 调它（本来就是独立） |
| 质量 / 服务 | 未交付 | 按同一三种模式新建进程 |

计算资源（DolphinScheduler、Hive 等）不是模块进程，是**普通/多租户下由使用方配置**的连接。独立模式用进程自己的配置文件。

## 3. 模块间只走 REST

禁止共库、禁止直连对方业务表。约定：

**组织平台对外（`multi` 时其它模块调用）**

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/internal/v1/registry/heartbeat` | 模块启动上报 `product` + `baseUrl` + 健康 |
| GET | `/internal/v1/authz/check` | `userId + tenantId + projectId + product + action` → 允许/拒绝 + 角色 |
| GET | `/internal/v1/context` | 校验模块令牌，返回当前租户/项目/用户摘要 |
| GET | `/internal/v1/projects/{code}/bindings` | 组织侧查询某项目已同步到哪些模块 |

**组织平台调模块（创建/停用项目时）**

| 方法 | 路径 | 用途 |
|---|---|---|
| PUT | `/internal/v1/projects/{code}` | **同一 project code** 在该模块落一份空项目 |
| DELETE | `/internal/v1/projects/{code}` | 模块侧停用或删除镜像 |

模块 `standalone` / `standard` 不调用组织平台，也没有服务注册。`standard` 建项目只写本库。

鉴权：组织 → 模块用登记时下发的 `module_token`；模块 → 组织用同一对令牌。浏览器只打「当前打开的那个模块」的 API，不让前端直打兄弟模块。

## 4. 权限（按模式裁）

权限仍跟角色走，不做跨产品勾选矩阵。

### 4.1 独立

- 没有登录、没有角色表。
- 写接口可关、可开，或用环境变量里的静态管理令牌。
- Header 里的 `X-Tenant-Id` / `X-Project-Id` / `X-User-Name` **只展示**，不鉴权、不落用户表。
- 现有 `Perms.java` 在此模式短路为放行（或只校验静态令牌）。

### 4.2 普通

组织层只保留：

| 角色 | 做什么 |
|---|---|
| 管理员 | 建用户、建项目、指定项目管理员 |
| 成员 | 只进被拉进的项目 |

产品层仍用各模块 manifest（仓建设：规范管理员 / 建模工程师 / 只读）。存在**本模块库**。

现有 `Perms` 的 `admin/modeler/viewer` 只覆盖仓建设，要改成「组织角色 + 产品角色」两段，与原型一致：项目管理员映射为该产品管理角色。

没有：平台用户、租户、授权码、选租户、模块可见范围（本环境开了哪些模块由启动配置决定）。

### 4.3 多租户

组织层只存在组织平台：

| 角色 | 做什么 |
|---|---|
| 平台用户 | 开停租户、开通模块上限、服务注册 |
| 租户管理员 | 用户、项目、模块启用与可见范围、计算资源 |
| 项目管理员 | 拉人、按产品派角 |
| 项目成员 | 只在可见且已派角的产品里做事 |

产品角色仍按 manifest，**存在组织平台**（`project_id + user_id + product + role`）。模块写操作先 `GET /internal/v1/authz/check`，再执行。模块库不存用户密码。路径全集见技术方案 [docs/tech/06-0.1.5-routes.md](../../../../tech/06-0.1.5-routes.md)。

可见范围（谁能看见模块菜单）也在组织平台。模块侧栏只问组织：当前人能看哪些 `product`。

## 5. 对照现在的代码要怎么改

### 后端 `services/api`

今天一个进程同时做了组织 + 仓建设。拆成：

1. **组织 API**（从现有抽出）：`AuthController`、`PlatformController`、`TenantAdminController`、用户/租户/项目/授权码、服务注册、`/internal/*`。`dwai.deployMode` 只对组织自己：`standard` | `multi`。
2. **仓建设 API**（留下 spec/table/AI/knowledge）：`MetaController` 里建模部分、`TableService`、`SpecService`。新增 `dwai.runMode=standalone|standard|multi`。
   - `standalone`：关掉 `/api/auth`、`/api/platform`、`TenantFilter` 对人的硬校验；项目用本地默认项目或请求里的展示 ID。
   - `standard`：保留本地用户，但删除租户 API；`implicitTenantId` 可继续当内部隔离键，**界面不出现租户**。
   - `multi`：删除本地登录；`TenantFilter` 改为调组织 `/internal/v1/authz/check`；启动时向组织心跳。
3. `DwaiProperties.DolphinScheduler` 从全局配置改到「普通：本环境一条；多租户：组织平台计算资源按租户读，仓建设 REST 去取」。
4. `Perms` 按产品 manifest 扩展，禁止再写死三套仓建设角色套所有模块。

`services/rules` 保持独立（天然 `standalone`），仓建设继续 REST 调用。

### 前端 `ui/src`

今天一个 SPA 含平台后台 + 工作台 + 建模。拆成：

1. **组织控制台**（原型 `org/`）：登录、租户、项目、模块、计算资源。`standard` 隐藏租户/平台字样。
2. **仓建设控制台**（原型 `prototype/`）：只有 `/w`。
   - `standalone`：无登录，无成员/用户菜单；顶栏可显示传入的项目名。
   - `standard`：本进程登录（可做极简页），无选租户。
   - `multi`：无登录页，未带组织会话则跳组织平台。

`ui/src/config/runtime.ts` 的 `multi | standard` 扩展为 `standalone | standard | multi`，以组织/模块各自启动配置为准，不要靠前端 `?mode=` 当生产开关。

### 引擎 `packages/engine`

继续无用户。不感知租户。三种模式共用。

## 6. 项目 ID

组织（或普通模式下的本模块）创建项目时生成 `project_id`。`multi` 下组织对每个已注册且已对该租户开通的模块 `PUT /internal/v1/projects/{同一 code}`（模块侧按 code 落成本地 id —— 组织主键不出组织，见 §3.3）。模块禁止另造项目号。跨模块 REST 只带 `tenant_id`（独立/普通可空）+ `project_id`。

## 7. 启动组合（例子）

- 只跑血缘：元数据 `standalone`。
- 只跑一家仓：仓建设 `standard`（或组织 `standard` + 仓建设 `multi` 指向本机组织，一般不必）。
- 套件：组织 `multi` + 仓建设 `multi` + 元数据 `multi`，模块启动登记，浏览器先打组织再打开模块。

## 8. 非目标

- 模块之间共库「省一次同步」。
- 前端直连兄弟模块 API。
- 独立模式实现完整用户管理。
- 用 Casdoor 替代组织平台（OIDC 只能作为组织平台的登录后端，不能让每个模块各自接一套）。
