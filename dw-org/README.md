# 租户管理

独立产品：登录、租户、用户、项目、模块开通、服务注册。与智仓**不共库**，只通过 REST 互调。

组织平台只有 **多租户 `multi`**。仓建设、数据地图另有独立 / 普通 / 多租户。三种模式怎么组合启动见仓库根 [README.md](../README.md)。

```
dw-org/
├── ui/                 前端 5171 · Dockerfile · nginx.conf
├── api/                后端 18080 · Dockerfile
├── packaging/          安装包 bin + conf
├── package.sh          all | api | ui
└── docker-compose.yml  org-api + org-ui
```

## 启动（仓库根目录）

需要 **Node 22**、**Java 21**、**Maven**。先装依赖：

```bash
npm install
```

`npm run dev:api:org` 实际执行 `mvn -f dw-org/api/pom.xml spring-boot:run`。模式靠环境变量，前端会从 API `/api/auth/config` 对齐。

### 多租户 `multi`（默认）

```bash
# 终端 1：API（H2：dw-org/api/data/dw_org）
npm run dev:api:org

# 终端 2：前端（Vite 把 /api 代理到 18080）
npm run dev:org
```

浏览器打开 http://127.0.0.1:5171/org/login

仓建设另开：`npm run dev:api:model` + `npm run dev:model`。工作台点「进入仓建设」跳到 http://127.0.0.1:5172/model#boot=… 。两边 `JWT_SECRET` 须一致。

| 账号 | 密码 | 说明 |
|---|---|---|
| 张三 | 123456 | 星河电商租户管理员 |
| 李四 | 123456 | 建模 / 启航 |
| 王五 | 123456 | 只读对比 |
| admin | admin123 | 平台用户 |

空库首次启动会写入演示数据（`DemoSeedRunner`）。健康检查：`GET http://127.0.0.1:18080/api/health`

空库首次启动会写入演示数据（`DemoSeedRunner`）。健康检查：`GET http://127.0.0.1:18080/api/health`

若本地曾用旧 schema 或旧文件名 `org.mv.db`，删掉 `dw-org/api/data/` 下的 H2 文件再启动（当前文件名 `dw_org`）。

也可在本目录用同一套名字：

```bash
npm run dev:org          # 只起前端（等价于 npm run dev）
npm run dev:api:org      # 只起 API（等价于 npm run dev:api）
```

## 打包与 Compose

```bash
./dw-org/package.sh          # 完整安装包 + 前后端分包  → dw-org/release/
./dw-org/package.sh api      # 只打后端  dw-org-0.1.3-api.tar.gz
./dw-org/package.sh ui       # 只打前端  dw-org-0.1.3-ui.tar.gz

# 仓库根
docker compose -f dw-org/docker-compose.yml up -d --build
# 控制台 http://127.0.0.1:8080/   API http://127.0.0.1:18080/
```

安装包改模式：`conf/env.sh` 里 `DW_AI_MODE=multi`。`JWT_SECRET` 须与智仓包相同。
