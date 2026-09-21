# 规则服务

无用户、无数据库。把 `packages/engine` 的纯函数用 HTTP 包一层，给智仓 Java API 调用（Java 不嵌 TypeScript）。

默认端口 **7080**。包名 `@dw-ai/rules`。

## 启动

```bash
# 仓库根（已 npm install）
npm run dev:rules
```

或 `npm run dev -w @dw-ai/rules`。探活：`GET http://127.0.0.1:7080/health`

智仓 API 通过 `RULES_BASE`（默认 `http://127.0.0.1:7080`）访问。不启动本进程时，只有「查询预览」等走规则的接口会失败；规范/建模页多数直接在前端用引擎，不受影响。

## 接口（均为 POST JSON）

| 路径 | 作用 |
|---|---|
| `/rules/access` | 按分层对外策略判查询能否出数 |
| `/rules/sql` | 生成 SQL 或按推荐路由到表 |
| `/rules/dwd-draft` | ODS → DWD 草稿 |
| `/rules/dws-draft` | DWD → DWS 草稿 |
| `/rules/metric-dup` | 指标是否重复 |
| `/rules/cluster` | 查询日志聚类，推荐物化 |

端口：`RULES_PORT`（默认 7080）。实现只有 `src/server.ts`。
