# 智仓（数仓建模）

规范中心、建模中心。**只画自己的页面**：数据地图、数据规范那些页面由组织平台的项目壳整合（壳从各服务拉菜单、按 `scope` 摆到工作台或项目那一层），本产品不再以 iframe 嵌 `dw-lineage`。与租户管理**不共库**。三种模式怎么组合启动见仓库根 [README.md](../README.md)。页面挂 `/model…`。

```
dw-model/
├── ui/                 前端 5172 · Dockerfile · nginx.conf
├── api/                后端 18081 · Dockerfile
├── rules/              规则 7080（可选）
├── packaging/          安装包 bin + conf
├── package.sh          all | api | ui
└── docker-compose.yml  model-api + model-ui
```

## 启动（仓库根目录）

需要 **Node 22**、**Java 21**、**Maven**。先 `npm install`。  
`npm run dev:api:model` = `mvn -f dw-model/api/pom.xml spring-boot:run`。开关：`DW_AI_MODE`，**不设时是 `standard`**（默认值写在 `api/src/main/resources/application.yml` 的 `deploy-mode`）——下面三节里只有 `multi` 那节必须显式带上模式变量。

### 多租户 `multi`

仓建设 **没有登录页**。先起组织，再起本模块，从组织点「进入仓建设」。

```bash
# 先起组织 5171 / 18080，再：
DW_AI_MODE=multi npm run dev:api:model   # 18081，项目按需从组织拉
VITE_RUN_MODE=multi npm run dev:model    # 5172
```

`multi` **不是默认值**：`npm run dev:api:model` 单跑出来的是 `standard`，看到的是本模块自己的
登录页、也不会跳组织。前端那一行不写也行（`VITE_RUN_MODE` 默认空 = 跟 API），这里与下面两节
保持同一种写法。

未带 `#boot=` 访问 5172 会跳回 http://127.0.0.1:5171/org/login 。

### 普通 `standard`（默认）

本进程本地账号，不启组织。打开 http://127.0.0.1:5172/model/login 。
首次启动只建默认 admin 账户，无演示数据；需要演示数据执行 `dw-model/packaging/bin/seed-demo.sh`（**手工触发**，启动不自动灌）。standard 下它只灌本环境的隐含租户（星河电商）那一套 —— 第二个租户「启航科技」在本模式下不可见，灌进去只会变成不可达数据。

```bash
DW_AI_MODE=standard npm run dev:api:model
VITE_RUN_MODE=standard npm run dev:model
```

### 独立 `standalone`

无登录、无租户切换。打开 http://127.0.0.1:5172/model 。

```bash
DW_AI_MODE=standalone npm run dev:api:model
VITE_RUN_MODE=standalone npm run dev:model
```

H2 文件：`dw-model/api/data/dw_mode`。健康检查：`GET http://127.0.0.1:18081/api/health`。

可选规则引擎（查询预览才需要）：

```bash
npm run dev:rules    # http://127.0.0.1:7080
```

数据地图在 [`dw-lineage/`](../dw-lineage/README.md)，作为**独立产品**由组织平台整合 ——
本进程既不挂它的菜单，也不嵌它的页面。

也可在本目录用同一套名字：`npm run dev:api:model` / `npm run dev:model`（等价于 `dev:api` / `dev`）。

## 打包与 Compose

```bash
./dw-model/package.sh          # 完整安装包 + 前后端分包  → dw-model/release/
./dw-model/package.sh api      # 只打后端  dw-model-0.1.3-api.tar.gz
./dw-model/package.sh ui       # 只打前端  dw-model-0.1.3-ui.tar.gz

# 仓库根
docker compose -f dw-model/docker-compose.yml up -d --build
# 控制台 http://127.0.0.1:8081/   API http://127.0.0.1:18081/
```

多租户须先起租户管理。`conf/env.sh` 里 `DW_AI_MODE`、`ORG_BASE_URL`、`JWT_SECRET`、`MODULE_TOKEN`
与组织一致。最后一项漏配的表现是「组织里建了项目，这里打开却是『还没有可进入的项目』」——
multi 下本模块**不建任何本地租户/项目**，全部从组织拉进来：用户第一次访问某个项目时，
`TenantFilter` 拿这枚令牌去问组织（`GET /internal/v1/projects/by-code/{code}?tenantCode=…`）
并落一份本地镜像（见根 [README](../README.md)）。
