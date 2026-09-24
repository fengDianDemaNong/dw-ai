# 数据库迁移（Flyway）

> 由 `bin/gen-index.sh` 于 2026-09-23 15:45:01 生成（HEAD `194259f`）。**不要手工编辑**，改完代码重跑脚本即可。
> **改迁移前必读**：`mysql/` 那份同时喂 H2（测试）与真 MySQL（部署），H2 认而 MySQL 不认的写法在测试里查不出来。
已知坑与替代写法写在 `dw-org/api/src/main/resources/db/migration/mysql/V8__grant_project_roles.sql` 顶部注释里；
约定本身见 `docs/adr/0002-schema-single-source.md`。



## .

### h2（仅 H2）

路径 = `./api/src/main/resources/db/migration/h2/` + 文件名。

- `V1__schema.sql` — V1 · schema
- `V2__init_data.sql` — V2 · init data
- `V3__local_auth.sql` — V3 · local auth

### mysql（H2（MODE=MySQL，测试）与真 MySQL（部署）共用同一份 —— 写法必须两边都认）

路径 = `./api/src/main/resources/db/migration/mysql/` + 文件名。

- `V1__schema.sql` — V1 · schema
- `V2__init_data.sql` — V2 · init data
- `V3__local_auth.sql` — V3 · local auth
- `V10__project_engines_and_knowledge.sql` — V10 · project engines and knowledge
- `V11__table_logic_and_lineage.sql` — V11 · table logic and lineage
- `V12__appearance_menu_color.sql` — V12 · appearance menu color
- `V13__refresh_tokens.sql` — V13 · refresh tokens
- `V14__member_product_roles.sql` — V14 · member product roles
- `V1__schema.sql` — V1 · schema
- `V8__grant_project_roles.sql` — V8 · grant project roles
- `V9__ai_caps_and_prompts.sql` — V9 · ai caps and prompts
- `V10__project_engines_and_knowledge.sql` — V10 · project engines and knowledge
- `V12__appearance_menu_color.sql` — V12 · appearance menu color
- `V13__service_registry.sql` — V13 · service registry
- `V14__refresh_tokens.sql` — V14 · refresh tokens
- `V15__member_product_roles.sql` — V15 · member product roles
- `V1__schema.sql` — V1 · schema
- `V8__grant_project_roles.sql` — V8 · grant project roles
- `V9__ai_caps_and_prompts.sql` — V9 · ai caps and prompts

### postgresql（仅 PostgreSQL）

路径 = `./api/src/main/resources/db/migration/postgresql/` + 文件名。

- `V1__schema.sql` — V1 · schema
- `V2__init_data.sql` — V2 · init data
- `V3__local_auth.sql` — V3 · local auth
- `V10__project_engines_and_knowledge.sql` — V10 · project engines and knowledge
- `V11__table_logic_and_lineage.sql` — V11 · table logic and lineage
- `V12__appearance_menu_color.sql` — V12 · appearance menu color
- `V13__refresh_tokens.sql` — V13 · refresh tokens
- `V14__member_product_roles.sql` — V14 · member product roles
- `V1__schema.sql` — V1 · schema
- `V8__grant_project_roles.sql` — V8 · grant project roles
- `V9__ai_caps_and_prompts.sql` — V9 · ai caps and prompts
- `V10__project_engines_and_knowledge.sql` — V10 · project engines and knowledge
- `V12__appearance_menu_color.sql` — V12 · appearance menu color
- `V13__service_registry.sql` — V13 · service registry
- `V14__refresh_tokens.sql` — V14 · refresh tokens
- `V15__member_product_roles.sql` — V15 · member product roles
- `V1__schema.sql` — V1 · schema
- `V8__grant_project_roles.sql` — V8 · grant project roles
- `V9__ai_caps_and_prompts.sql` — V9 · ai caps and prompts

## 安装包 / 初始化脚本（非 Flyway 托管）

- `infra/db/mysql-init.sql`
- `infra/db/postgres-init.sql`

