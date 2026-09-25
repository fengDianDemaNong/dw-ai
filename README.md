# 智仓 DW-AI

每个**已交付服务**同一套目录约定：前端 `ui/`、后端 `api/`、安装脚本 `packaging/`。  
本仓库交付三套：**租户管理**、**智仓**、**数据地图**（`dw-lineage`）。

```
dw-org/              租户管理     UI 5171 / Compose 8080 · API 18080 · 库 dw_org
dw-model/            智仓         UI 5172 / Compose 8081 · API 18081 · 规则 7080 · 库 dw_mode
dw-lineage/          数据地图     UI 5173 · API 18082 · 库 dw_lineage
packages/engine/     共享规则库（不单独启动）
docs/product/        可点击原型（不是实现）
```

后端是 **Java 21**（Spring Boot）。根目录 `npm run dev:api:org` 只是 `package.json` 里写成 `mvn -f dw-org/api/pom.xml spring-boot:run`。

## 三种运行模式

同一套代码，启动时用环境变量选一种，**运行中不要改**。

| 模式 | 配置值 | 谁管登录 | 组织平台 | 典型用法 |
|---|---|---|---|---|
| 独立 | `standalone` | 不管人，无登录 | 不启 | 只跑仓建设或血缘 |
| 普通 | `standard` | 本模块本地账号 | 不启 | 单企业自用仓建设 / 血缘 |
| 多租户 | `multi`（仓建设/血缘默认；组织唯一模式） | 组织平台 | **必须先起** | 套件：先登录组织，再进仓建设 / 血缘 |

组织平台**只有多租户**（它的业务就是管租户）。仓建设、血缘三种都能跑。独立 / 普通下各产品只显示自己的页面：仓建设 `/model…`，数据地图 `/lineage…`，组织 `/org…`。

后端开关：三个模块统一用 `DW_AI_MODE`（组织平台恒为 multi，设了也不变）；血缘另有更具体的 `LINEAGE_RUN_MODE`，两个都设时以它为准，也可用 Spring profile（如 `application-multi.yml`）。  
前端会调 `/api/auth/config` 跟上；为避免第一屏闪错模式，可同时设 `VITE_RUN_MODE`。

**`multi` 还必须配 `MODULE_TOKEN`（三个模块同一个值）**，`standalone` / `standard` 不需要。
仓建设的租户与项目**全部从组织取**（multi 下它不建任何本地数据）：用户在模块里第一次访问
某个项目时，模块自己拿这枚令牌去组织拉一次
（`GET /internal/v1/projects/by-code/{code}?tenantCode=…`）——这条链路唯一的门禁就是这枚令牌，
未配置时 `/internal/v1/**` 一律 401（见 [ADR-0012](docs/tech/adr/0012-module-token-gate.md)）。

不配的表现**是静默的**，值得记住：组织里项目建得好好的，仓建设打开却是「还没有可进入的项目」，
数据地图报「租户编码未同步」，日志里才有一行 401。改完必须**重启进程**才生效。

> 旧版本是反过来的：组织在「建项目 / 改许可」时主动推给模块，模块启动后还要心跳上报自己的
> 后端地址，所以库空了要靠心跳全量补发。现在组织**不需要知道模块的后端地址**（它只需要产品的
> **页面**地址，用于把页面嵌进自己的壳），推送与心跳都已删除，同步方向是模块来拉。

本地开发需要 **Node 22**、**Java 21**、**Maven**。先在仓库根：

```bash
npm install
```

每个 API / 前端各开一个终端（`&&` 写在同一行会卡住）。改过表结构后删掉 `dw-org/api/data/`、`dw-model/api/data/` 再启动。

---

### 1. 多租户 `multi`（默认，套件）

先组织，再仓建设。浏览器只从组织进。

```bash
# ⚠️ multi 必配：三个 API 终端都要设 MODULE_TOKEN，且必须是同一个值。
# 下面这行只在终端 1 执行一次，再把生成的值复制给终端 3、终端 5 ——
# 三个终端各自跑一次 openssl rand 会得到不同的值，照样不通。
export MODULE_TOKEN=$(openssl rand -hex 32)

# 终端 1  组织 API   18080
npm run dev:api:org

# 终端 2  组织前端   http://127.0.0.1:5171/org/login
npm run dev:org

# 终端 3  仓建设 API  18081（项目按需从 18080 拉）
npm run dev:api:model

# 终端 4  仓建设前端  5172（未带会话会跳回 5171）
npm run dev:model
```

打开 http://127.0.0.1:5171/org/login ，张三 / 123456，再点「进入仓建设」。不要直接打开 5172 登录（multi 下仓建设没有登录页）。

账号：`张三` / `李四` / `王五` + `123456`；平台用户 `admin` / `123456`。

数据地图（可选）：

```bash
# 终端 5  血缘 API  18082  profile=multi（同样要设 MODULE_TOKEN，值与终端 1 相同）
npm run dev:api:lineage

# 终端 6  血缘前端  5173
npm run dev:lineage
```

这两个脚本是**按 multi 配好的**：`dev:api:lineage` 带 `-Dspring-boot.run.profiles=multi`；
`dev:lineage` 跑 `dev:multi`，5173 的代理也指向 18082，两边对得上。

**端口与 profile 无关**：三个后端的端口都写成单文件 `${SERVER_PORT:18xxx}`
（org 18080 / model 18081 / lineage 18082），**没有任何 profile 覆盖它**。
所以带不带 `multi` 都落在 18082。

**数据地图的默认模式是 `standard`（不是 `multi`）**，与仓建设相反。上面这两个脚本
是套件（multi）专用入口；单跑血缘见下方「普通 / 独立」两节，用的是显式 `mvn` 命令。

---

### 2. 普通 `standard`（本模块自己登录，无租户字样）

组织平台不要用这个模式（组织只有 multi）。

**只跑仓建设**（自带账号，不启组织）：

```bash
DW_AI_MODE=standard npm run dev:api:model
VITE_RUN_MODE=standard npm run dev:model
```

打开 http://127.0.0.1:5172/model/login 。

**只跑血缘**（自带租户/项目壳）：

```bash
LINEAGE_RUN_MODE=standard mvn -f dw-lineage/api/pom.xml spring-boot:run
npm run dev:standard -w sql-tools
```

前端默认 http://127.0.0.1:5173 ，后端默认 18082。

---

### 3. 独立 `standalone`（无登录）

组织平台不要用这个模式。

**只跑仓建设**（无登录，头里的人/项目只展示）：

```bash
DW_AI_MODE=standalone npm run dev:api:model
VITE_RUN_MODE=standalone npm run dev:model
```

打开 http://127.0.0.1:5172/model 。

**只跑血缘**（默认是普通模式 standard，带本模块账号）：

```bash
# API 默认 LINEAGE_RUN_MODE=standard（本地账号 admin / 123456），端口 18082
mvn -f dw-lineage/api/pom.xml spring-boot:run

npm run dev:standard -w sql-tools
```

打开 http://127.0.0.1:5173/lineage 。

要**独立模式**（无登录，起来就能用）就把两边都换成 standalone —— 只改前端是不够的，
后端不设模式变量时仍是 standard（血缘认两个变量：`LINEAGE_RUN_MODE` 优先，跟仓建设统一设
`DW_AI_MODE` 也行）：

```bash
LINEAGE_RUN_MODE=standalone mvn -f dw-lineage/api/pom.xml spring-boot:run

npm run dev:standalone -w sql-tools
```

---

## 交付方式

| 方式 | 命令 | 产物 |
|---|---|---|
| 完整安装包（Java 出页面） | `./dw-org/package.sh` / `./dw-model/package.sh` | `dw-org-0.1.3.tar.gz` / `dw-model-0.1.3.tar.gz` |
| 只打后端 | `./dw-org/package.sh api` | `dw-org-0.1.3-api.tar.gz` |
| 只打前端 | `./dw-org/package.sh ui` | `dw-org-0.1.3-ui.tar.gz` |
| Compose（本服务） | `docker compose -f dw-org/docker-compose.yml up -d --build` | `org-api` + `org-ui` |
| Compose（全套） | `docker compose up -d --build` | 两套 API + 两套 Nginx |

安装包改模式：`conf/env.sh` 里的 `DW_AI_MODE`（三个模块统一）；血缘另有 `LINEAGE_RUN_MODE`，两个都设时以它为准。

**服务间令牌 `MODULE_TOKEN` 配在哪**（只有 `multi` 需要，三个模块的值必须一致）：

| 部署方式 | 配置位置 |
|---|---|
| 本地开发 | 每个 API 终端 `export MODULE_TOKEN=...`（见上方各模式命令） |
| Compose | 仓库根 `.env`（`.env.example` 里有这一项），由 compose 透传给三个 API |
| 安装包 | 三个包各自的 `conf/env.sh` 里加 `export MODULE_TOKEN='...'` |

安装包这一行要**写三遍**：`dw-org/`、`dw-model/`、`dw-lineage/` 是三个独立产物，
各自的 `packaging/conf/env.sh` 互不相干，没有共享的配置来源（对照 `METADATA_SECRET_KEY`
等既有项）。真要一处配全，得靠外部的统一环境变量（systemd `EnvironmentFile=`）。

## 文档

| 模块 | 说明 |
|---|---|
| [dw-org/](dw-org/README.md) · [dw-org/ui](dw-org/ui/README.md) · [dw-org/api](dw-org/api/README.md) | 租户管理 |
| [dw-model/](dw-model/README.md) · [dw-model/ui](dw-model/ui/README.md) · [dw-model/api](dw-model/api/README.md) | 智仓 |
| [dw-lineage/](dw-lineage/README.md) | 数据地图 |
| [packages/engine](packages/engine/README.md) | 引擎库 |
| [docs/product/](docs/product/) | 产品 0.2.0 |
| [docs/user/产品手册.md](docs/user/产品手册.md) | 功能手册 |
| [docs/index/](docs/index/README.md) | 代码索引：按模块列出每个类/组件的路径与职责，找文件用 |

```bash
npm run proto          # 0.2.0 原型：组织 4234 / 仓建设 4233
bin/gen-index.sh       # 重新生成 docs/index/（改完代码跑一次，几秒钟）
```

Casdoor 为可选项。未验证前不要设 `SECURITY_MODE=oidc`。
