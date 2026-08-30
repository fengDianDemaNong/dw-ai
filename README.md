# 智仓 DW-AI

多租户数据 AI 平台。当前交付：**规范中心**、**建模中心**（以及登录 / 工作台 / 平台管理）。开发中心、沉淀优化、数据服务尚未上线。

**0.1.2**：默认嵌入式 H2（也可 MySQL / PostgreSQL）；演示数据需手动灌入；安装包业务 jar 与依赖分离。功能说明（含截图）：[docs/user/产品手册.md](docs/user/产品手册.md)。安装与升级：[docs/user/使用说明.md](docs/user/使用说明.md)。

## 文档与原型

- 产品设计：[docs/product/](docs/product/) — 当前设计 **0.1.1**，底座发布 **0.1.2**
- 技术方案：[docs/tech/](docs/tech/)（含 [0.1.2](docs/tech/03-0.1.2.md)）

```bash
npm run proto          # 产品原型 0.1.1 → http://127.0.0.1:4183/
npm run proto -- 0.1.0 # 冻结的仓建设原型 → 4173
npm run dev            # 功能实现 → http://127.0.0.1:5173/
```

## 本地开发（默认 H2，无需 Docker 数据库）

```bash
npm install
# 终端 1
cd services/api && mvn spring-boot:run
# 终端 2：灌演示数据（须 API 已启动过一次建表）
cd services/api && java -cp "target/dw-ai-api-0.1.2.jar:target/lib/*" com.dwai.platform.SeedMain
# 终端 3
npm run dev:api
```

浏览器 http://localhost:5173 ，张三 / 123456。H2 文件在 `services/api/data/`（`mvn` 工作目录）。

接 MySQL 或 PostgreSQL：启动前设 `DB_URL` / `DB_USER` / `DB_PASSWORD`。

Docker：

```bash
docker compose up -d --build          # API 用 H2
docker compose --profile postgres up -d
```

安装包：

```bash
./package.sh                 # release/dw-ai-0.1.2.tar.gz 与 dw-ai-0.1.2-app.tar.gz
# 解压后 ./bin/start.sh && ./bin/seed.sh → http://127.0.0.1:8080/
```

分项：[ui/README.md](ui/README.md)、[services/api/README.md](services/api/README.md)。

Casdoor 为可选项。未验证前不要设 `SECURITY_MODE=oidc`。

## 技术

Vue 3 + Vite + TypeScript + Ant Design Vue。元数据在 Java API + H2 / MySQL / PostgreSQL；命名 / 草案 / 校验在 `@dw-ai/engine`。
