# 智仓 DW-AI

每个**已交付服务**同一套目录约定：前端 `ui/`、后端 `api/`、安装脚本 `packaging/`。  
本仓库交付三套：**租户管理**、**智仓**、**数据地图**（`dw-lineage`）。

```
dw-org/              租户管理     UI 5171 / Compose 8080 · API 18080 · 库 dw_org
dw-model/            智仓         UI 5172 / Compose 8081 · API 18081 · 规则 7080 · 库 dw_mode
dw-lineage/          数据地图     UI 5175（独立/普通 5173）· API 18082（独立默认 8080）· 库 dw_lineage
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

后端开关：`DW_AI_MODE`（组织 / 仓建设），血缘用 `LINEAGE_RUN_MODE` 或 Spring profile。  
前端会调 `/api/auth/config` 跟上；为避免第一屏闪错模式，可同时设 `VITE_RUN_MODE`。

本地开发需要 **Node 22**、**Java 21**、**Maven**。先在仓库根：

```bash
npm install
```

每个 API / 前端各开一个终端（`&&` 写在同一行会卡住）。改过表结构后删掉 `dw-org/api/data/`、`dw-model/api/data/` 再启动。

---

### 1. 多租户 `multi`（默认，套件）

先组织，再仓建设。浏览器只从组织进。

```bash
# 终端 1  组织 API   18080
npm run dev:api:org

# 终端 2  组织前端   http://127.0.0.1:5171/org/login
npm run dev:org

# 终端 3  仓建设 API  18081（向 18080 心跳）
npm run dev:api:model

# 终端 4  仓建设前端  5172（未带会话会跳回 5171）
npm run dev:model
```

打开 http://127.0.0.1:5171/org/login ，张三 / 123456，再点「进入仓建设」。不要直接打开 5172 登录（multi 下仓建设没有登录页）。

账号：`张三` / `李四` / `王五` + `123456`；平台用户 `admin` / `admin123`。

数据地图（可选）：

```bash
# 终端 5  血缘 API  18082  profile=multi
npm run dev:api:lineage

# 终端 6  血缘前端  5175
npm run dev:lineage
```

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
LINEAGE_RUN_MODE=standard mvn -f dw-lineage/sql-tools/pom.xml spring-boot:run
(cd dw-lineage/sql-tools-vue && pnpm install && pnpm run dev:standard)
```

前端默认 http://127.0.0.1:5173 ，后端默认 8080。

---

### 3. 独立 `standalone`（无登录）

组织平台不要用这个模式。

**只跑仓建设**（无登录，头里的人/项目只展示）：

```bash
DW_AI_MODE=standalone npm run dev:api:model
VITE_RUN_MODE=standalone npm run dev:model
```

打开 http://127.0.0.1:5172/model 。

**只跑血缘**（默认就是独立）：

```bash
# API 默认 LINEAGE_RUN_MODE=standalone，端口 8080
mvn -f dw-lineage/sql-tools/pom.xml spring-boot:run

(cd dw-lineage/sql-tools-vue && pnpm install && pnpm run dev:standalone)
```

打开 http://127.0.0.1:5173/lineage 。

---

## 交付方式

| 方式 | 命令 | 产物 |
|---|---|---|
| 完整安装包（Java 出页面） | `./dw-org/package.sh` / `./dw-model/package.sh` | `dw-org-0.1.3.tar.gz` / `dw-model-0.1.3.tar.gz` |
| 只打后端 | `./dw-org/package.sh api` | `dw-org-0.1.3-api.tar.gz` |
| 只打前端 | `./dw-org/package.sh ui` | `dw-org-0.1.3-ui.tar.gz` |
| Compose（本服务） | `docker compose -f dw-org/docker-compose.yml up -d --build` | `org-api` + `org-ui` |
| Compose（全套） | `docker compose up -d --build` | 两套 API + 两套 Nginx |

安装包改模式：`conf/env.sh` 里的 `DW_AI_MODE`（血缘 `LINEAGE_RUN_MODE`）。

## 文档

| 模块 | 说明 |
|---|---|
| [dw-org/](dw-org/README.md) · [dw-org/ui](dw-org/ui/README.md) · [dw-org/api](dw-org/api/README.md) | 租户管理 |
| [dw-model/](dw-model/README.md) · [dw-model/ui](dw-model/ui/README.md) · [dw-model/api](dw-model/api/README.md) | 智仓 |
| [dw-lineage/](dw-lineage/README.md) | 数据地图 |
| [packages/engine](packages/engine/README.md) | 引擎库 |
| [docs/product/](docs/product/) | 产品 0.2.0 |
| [docs/user/产品手册.md](docs/user/产品手册.md) | 功能手册 |

```bash
npm run proto          # 0.2.0 原型：组织 4234 / 仓建设 4233
```

Casdoor 为可选项。未验证前不要设 `SECURITY_MODE=oidc`。
