# 租户管理 · API

Java 21 · Spring Boot 3.3 · MyBatis-Plus · Flyway。构件 `dw-org`。  
默认端口 **18080**。未设 `DB_URL` 时用 H2 文件库 `dw-org/api/data/dw_org`（相对本模块工作目录）。  
**不要**把 `DB_URL` 指到智仓的库；MySQL/PostgreSQL 请用独立库名 `dw_org`。

身份、租户、项目、开通、服务注册的真源在这里。不存数仓分层/模型表。

## 启动

```bash
# 仓库根 · 默认 multi
npm run dev:api:org

# 普通模式
DW_AI_MODE=standard npm run dev:api:org

# 或
cd dw-org/api && mvn spring-boot:run
```

健康检查：`GET http://127.0.0.1:18080/api/health`

空库只建默认 admin 账户（admin / 123456），不灌演示数据；张三 / 李四 / 王五需执行 `packaging/bin/seed-demo.sh`。Flyway 只建表。

## 常用变量

| 变量 | 含义 | 默认 |
|---|---|---|
| `SERVER_PORT` | 监听端口 | `18080` |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 元数据库 | 空 → H2 |
| `DW_AI_MODE` / `DW_AI_RUN_MODE` | `multi` / `standard`（无 standalone） | `multi` |
| `PUBLIC_BASE_URL` | 对外前端地址 | `http://127.0.0.1:5171` |
| `JWT_SECRET` | 签发令牌；须与智仓 API 一致 | 开发默认值 |
| `SECURITY_MODE` | `dev` 或 `oidc` | `dev` |
| `BOOTSTRAP_ADMIN_USER` / `BOOTSTRAP_ADMIN_PASSWORD` | 空库平台用户 | `admin` / `123456` |

## 给智仓的接口

智仓进程调用（无浏览器）：

- `GET /internal/v1/authz/check` — 鉴权
- `POST /internal/v1/registry/heartbeat` — 登记
- 本服务创建/删除项目时 `PUT`/`DELETE` 已登记模块的 `/internal/v1/projects/{project_code}`

联调前端见 [../ui/README.md](../ui/README.md)。安装包见 [../package.sh](../package.sh)。
