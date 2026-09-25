# 智仓 · 前端

Vue 3 + Vite + Ant Design Vue。包名 `@dw-ai/ui`。  
规范中心、建模中心、项目设置。多租户下**没有登录页**（跳租户管理）。

## 启动

在**仓库根**：

```bash
npm install                 # 只需一次
npm run dev:model           # http://127.0.0.1:5172/
```

等价：`npm run dev -w @dw-ai/ui`，或 `cd dw-model && npm run dev`。

Vite 把 `/api`、`/internal` 代理到 `http://127.0.0.1:18081`。请先启动 [../api/README.md](../api/README.md)。

多租户联调还要起租户管理（[../../dw-org/README.md](../../dw-org/README.md)），从 5171 带 `#boot=` 进来。

## 环境变量

`dev` 脚本已带：

| 变量 | 默认 | 含义 |
|---|---|---|
| `VITE_PRODUCT` | `warehouse` | 固定为本产品 |
| `VITE_RUN_MODE` | 空（跟 API） | `multi` / `standard` / `standalone` |
| `VITE_API_BASE_URL` | 空（走代理） | 可选直连 API |
| `VITE_BASE_URL` | 空 | **本控制台自己的对外地址**；容器启动时渲染进 `config.json` |
| `VITE_RULES_BASE` | 空 | 规则服务；空则走同源 `/api` 编排 |

「未登录回门户」的地址**不再来自环境变量**，改为运行期发现（`config/product.ts` 的
`orgOrigin` 三级回落）。要改地址请到组织平台的「服务注册」页改，不需要重新构建前端。

本前端只画自己的页面：数据地图、数据规范那些页面由组织平台的项目壳整合
（壳从各服务拉菜单、按 `scope` 摆到工作台或项目那一层），这里不再以 iframe 嵌别人的页面。

运行时配置：容器启动时由 `docker-entrypoint.d/25-app-config.sh` 把
`VITE_API_BASE_URL` / `VITE_BASE_URL` 渲染成 `/config.json`；dev 下读不到该文件则回落到
`.env.local` 里的 `VITE_*`。优先级 `config.json 非空字段 > 构建期 VITE_* > 内置默认`，
详见 `src/config/appConfig.ts`。

构建：`npm run build -w @dw-ai/ui`（`package.sh` 会打进安装包 `web/`）。  
Docker：`docker build -f dw-model/ui/Dockerfile .`（上下文必须是仓库根）。

E2E：`npm run test:e2e -w @dw-ai/ui`（需 5172 已起）。手册截图：`node scripts/capture-user-docs.mjs`。
