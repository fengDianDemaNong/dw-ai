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
| `VITE_ORG_ORIGIN` | `http://127.0.0.1:5171` | 自己的 origin |
| `VITE_WAREHOUSE_ORIGIN` | `http://127.0.0.1:5172` | 「进入仓建设」跳转地址 |
| `VITE_RUN_MODE` | 空（跟 API `/api/auth/config`） | `multi` / `standard` |
| `VITE_API_BASE` | 空（走代理） | 若设成绝对地址则不再走 Vite 代理 |

构建：`npm run build -w @dw-org/ui`。Docker 构建上下文必须是仓库根：`docker build -f dw-org/ui/Dockerfile .`
