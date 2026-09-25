# 智仓 · API

Java 21 · Spring Boot 3.3 · MyBatis-Plus · Flyway。构件 `dw-model`（应用名 `dw-warehouse-api`）。  
默认端口 **18081**。未设 `DB_URL` 时用 H2 文件库 `dw-model/api/data/dw_mode`。  
**不要**把 `DB_URL` 指到租户管理的库；MySQL/PostgreSQL 请用独立库名 `dw_mode`。

存分层规范、模型表、版本、知识库等。人不在这里建（多租户下验组织签发的 JWT）。

## 启动

```bash
# 仓库根 · 默认 standard（不设 DW_AI_MODE 就是这个）
npm run dev:api:model

# 多租户 / 独立
DW_AI_MODE=multi npm run dev:api:model
DW_AI_MODE=standalone npm run dev:api:model

# 或
cd dw-model/api && mvn spring-boot:run
```

健康检查：`GET http://127.0.0.1:18081/api/health`

空库会灌演示建模数据，并向组织平台心跳（`ORG_BASE_URL`，默认 `http://127.0.0.1:18080`）。组织 API 未起时心跳失败不影响本机建模接口。

## 常用变量

| 变量 | 含义 | 默认 |
|---|---|---|
| `SERVER_PORT` | 监听端口 | `18081` |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 元数据库 | 空 → H2 |
| `ORG_BASE_URL` | 租户管理 API | `http://127.0.0.1:18080` |
| `PUBLIC_BASE_URL` | 对外前端 | `http://127.0.0.1:5172` |
| `JWT_SECRET` | 验组织令牌；须与 org API 一致 | 开发默认值 |
| `RULES_BASE` | 规则服务 | `http://127.0.0.1:7080` |
| `DW_AI_MODE` | `multi` / `standard` / `standalone` | `standard` |
| `SR_ENABLED` | 是否把预览 SQL 打到 StarRocks | `false` |

组织创建项目时会打本服务：`PUT /internal/v1/projects/{project_code}`。  
查询预览走 [../rules/README.md](../rules/README.md) 的 access / sql。

联调前端见 [../ui/README.md](../ui/README.md)。使用说明：[docs/user/使用说明.md](../../docs/user/使用说明.md)。安装包见 [../package.sh](../package.sh)。

## Docker

```bash
# 仓库根，默认 H2（compose 里 API 仍映射 8080，与本地 18081 不同）
docker compose up -d --build
```
