# 技术栈（生产底座）

对齐产品：`docs/product/versions/0.1.0/spec/`。控制台继续 Vue；规则仍在 TypeScript（`@dw-ai/engine`），Java 只编排、落库、鉴权。

| 层 | 选型 | 版本 |
|---|---|---|
| 控制台 | Vue 3 + Vite + Ant Design Vue | 租户管理 `dw-org/ui`；智仓 `dw-model/ui` |
| API | Java 21 · Spring Boot 3.3 · MyBatis-Plus 3.5 | 租户管理 `dw-org/api`；智仓 `dw-model/api` |
| 迁移 | Flyway 10 | `classpath:db/migration/{mysql,postgresql}`（H2 与 MySQL 共用） |
| 库 | H2（默认，分文件）· MySQL 8 · PostgreSQL 16 | 租户管理 `dw_org` / `data/dw_org`；智仓 `dw_mode` / `data/dw_mode` |
| 登录 | Casdoor OIDC（授权码 + PKCE） | 可选；未配时开发 JWT |
| 规则 | Node（`dw-model/rules`） | 后接，不在阶段 4 必开 |

明确不做（本阶段）：StarRocks、DolphinScheduler、用 Java 重写引擎。
