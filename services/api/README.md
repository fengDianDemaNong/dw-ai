# 智仓 API（`dw-ai-api`）

Java 17 · Spring Boot 3.3 · MyBatis-Plus · Flyway。默认端口 **8080**。  
元数据库：**未设 `DB_URL` 时用 H2 文件库**；也可 MySQL 8、PostgreSQL 16。  
使用说明：[docs/user/使用说明.md](../../docs/user/使用说明.md)。

## 本地启动

```bash
cd services/api
mvn spring-boot:run
```

H2 文件默认 `./data/dwai`（相对进程工作目录）。Flyway 只建表。空库会自动创建平台用户 `admin` / `admin123`。演示租户：

```bash
# 先完成一次启动建表，再（可另开终端，H2 AUTO_SERVER 允许并行）
java -cp "target/dw-ai-api-0.1.3.jar:target/lib/*" com.dwai.platform.SeedMain
```

| 变量 | 含义 | 默认 |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 元数据库 | 空 → H2，用户 `sa` |
| `DB_TYPE` | `h2` / `mysql` / `postgresql` | 按 URL 判断 |
| `DW_AI_HOME` | H2 文件根目录 | `user.dir` |
| `SECURITY_MODE` | `dev` 或 `oidc` | `dev` |
| `CORS_ORIGINS` | 控制台来源 | 本机 5173 / 80 |
| `WEB_STATIC_DIR` | 托管控制台静态目录 | 空 |
| `BOOTSTRAP_ADMIN_USER` / `BOOTSTRAP_ADMIN_PASSWORD` | 空库初始平台用户 | `admin` / `admin123` |

健康检查：`GET http://127.0.0.1:8080/api/health`

联调控制台：仓库根 `npm run dev:api`。

## Docker

```bash
# 仓库根，默认 H2
docker compose up -d --build
docker compose --profile postgres up -d
docker compose --profile mysql up -d
```

外部库：在 `.env` 写 `DB_URL` 后 `docker compose up -d --build api ui`。
