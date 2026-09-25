# 租户管理 · 前端

Vue 3 + Vite + Ant Design Vue。包名 `@dw-org/ui`。只服务登录、选租户、平台管理、租户工作台，**不含**规范/建模页。

## 启动

在**仓库根**（不要只进本目录 `npm install`，workspace 依赖 `packages/engine`）：

```bash
npm install                 # 只需一次
npm run dev:org             # http://127.0.0.1:5171/
```

等价：`npm run dev -w @dw-org/ui`，或 `cd dw-org && npm run dev`。

Vite 把 `/api`、`/internal` 代理到 `http://127.0.0.1:18080`（见 `vite.config.ts`）。请先启动 [../api/README.md](../api/README.md)。

## 环境变量

复制 `.env.example` 为 `.env.local`（可选）。`dev` 脚本已带：

| 变量 | 默认 | 含义 |
|---|---|---|
| `VITE_PRODUCT` | `org` | 固定为本产品 |
| `VITE_RUN_MODE` | 空（跟 API `/api/auth/config`） | `multi` / `standard` |
| `VITE_API_BASE_URL` | 空（走代理） | 若设成绝对地址则不再走 Vite 代理 |
| `VITE_BASE_URL` | 空 | **本控制台自己的对外地址**；容器启动时渲染进 `config.json` |

「进入仓建设 / 数据地图」的跳转地址**不再来自环境变量**，改为运行期问服务注册表
（`GET /api/services`，见 `config/product.ts` 的 `configureProductOrigins`）。要改地址请到
平台后台的「服务注册」页改，改完刷新即可，不需要重新构建前端。

运行时配置：容器启动时由 `docker-entrypoint.d/25-app-config.sh` 把
`VITE_API_BASE_URL` / `VITE_BASE_URL` 渲染成 `/config.json`；dev 下读不到该文件则回落到
`.env.local` 里的 `VITE_*`。优先级 `config.json 非空字段 > 构建期 VITE_* > 内置默认`，
详见 `src/config/appConfig.ts`。

构建：`npm run build -w @dw-org/ui`。Docker 构建上下文必须是仓库根：`docker build -f dw-org/ui/Dockerfile .`
