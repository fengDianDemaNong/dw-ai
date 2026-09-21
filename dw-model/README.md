# 智仓（数仓建模）

规范中心、建模中心。数据地图只在 **multi** 下以 iframe 嵌 `dw-lineage`。与租户管理**不共库**。三种模式怎么组合启动见仓库根 [README.md](../README.md)。页面挂 `/model…`。

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
`npm run dev:api:model` = `mvn -f dw-model/api/pom.xml spring-boot:run`。开关：`DW_AI_MODE`。

### 多租户 `multi`（默认）

仓建设 **没有登录页**。先起组织，再起本模块，从组织点「进入仓建设」。

```bash
# 先起组织 5171 / 18080，再：
npm run dev:api:model     # 18081，向组织心跳
npm run dev:model         # 5172
```

未带 `#boot=` 访问 5172 会跳回 http://127.0.0.1:5171/org/login 。

### 普通 `standard`

本进程本地账号，不启组织。打开 http://127.0.0.1:5172/model/login 。

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

数据地图实现在 [`dw-lineage/`](../dw-lineage/README.md)。本产品只做 iframe 壳。

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

多租户须先起租户管理。`conf/env.sh` 里 `DW_AI_MODE`、`ORG_BASE_URL`、`JWT_SECRET` 与组织一致。
