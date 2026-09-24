# 智仓 · 前端

Vue 3 + Vite + Ant Design Vue。包名 `@dw-ai/ui`。  
规范中心、建模中心、数据地图 iframe、项目设置。多租户下**没有登录页**（跳租户管理）。

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
| `VITE_ORG_ORIGIN` | `http://127.0.0.1:5171` | 未登录跳转 |
| `VITE_WAREHOUSE_ORIGIN` | `http://127.0.0.1:5172` | 自己的 origin |
| `VITE_LINEAGE_ORIGIN` | `http://127.0.0.1:5173` | 数据地图 iframe |
| `VITE_RUN_MODE` | 空（跟 API） | `multi` / `standard` / `standalone` |
| `VITE_API_BASE` | 空（走代理） | 可选直连 API |
| `VITE_RULES_BASE` | 空 | 规则服务；空则走同源 `/api` 编排 |

构建：`npm run build -w @dw-ai/ui`（`package.sh` 会打进安装包 `web/`）。  
Docker：`docker build -f dw-model/ui/Dockerfile .`（上下文必须是仓库根）。

E2E：`npm run test:e2e -w @dw-ai/ui`（需 5172 已起）。手册截图：`node scripts/capture-user-docs.mjs`。
