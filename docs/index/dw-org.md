# dw-org（组织平台 · 后端）

> 由 `bin/gen-index.sh` 于 2026-09-27 18:15:33 生成（HEAD `04d51fd`）。**不要手工编辑**，改完代码重跑脚本即可。
> 共 48 个类。**路径 = 源码根 `dw-org/api/src/main/java/` + 下表路径**；分组标题是包名（已省略 `com/dwai/platform/` 这类公共前缀）。测试清单见 [tests.md](tests.md)。


## (根包)

- `com/dwai/platform/DwaiApplication.java` — DwaiApplication [启动类] · `main`
- `com/dwai/platform/DwaiProperties.java` — DwaiProperties [配置绑定] · `@ConfigurationProperties(dwai)`

## auth

- `com/dwai/platform/auth/AuthController.java` — AuthController [HTTP 接口] · 前缀 `/api/auth`, `/api/v1/auth` → `GET /config`, `POST /login`, `POST /refresh`, `POST /logout`, `GET /tenants`, `POST /select-tenant`, `POST /enter-tenant`, `GET /me`
- `com/dwai/platform/auth/AuthService.java` — AuthService [业务服务]
- `com/dwai/platform/auth/BootstrapAdminRunner.java` — BootstrapAdminRunner [组件]
- `com/dwai/platform/auth/DemoSeedRunner.java` — DemoSeedRunner [组件] 演示数据灌入器。
- `com/dwai/platform/auth/JwtIssuer.java` — JwtIssuer [组件]
- `com/dwai/platform/auth/JwtSessionEpoch.java` — JwtSessionEpoch [组件]
- `com/dwai/platform/auth/LlmCrypto.java` — LlmCrypto [组件]
- `com/dwai/platform/auth/MeController.java` — MeController [HTTP 接口] · 前缀 `/api/me`, `/api/v1/me` → `PUT /password`
- `com/dwai/platform/auth/RefreshTokenService.java` — RefreshTokenService [业务服务]
- `com/dwai/platform/auth/SecurityConfig.java` — SecurityConfig [装配]
- `com/dwai/platform/auth/TenantContext.java` — TenantContext
- `com/dwai/platform/auth/TenantFilter.java` — TenantFilter

## db

- `com/dwai/platform/db/MetaDbEnvironmentPostProcessor.java` — MetaDbEnvironmentPostProcessor [启动期处理]

## internal

- `com/dwai/platform/internal/InternalController.java` — InternalController [HTTP 接口] · 前缀 `/internal/v1` → `POST /auth/refresh`, `POST /auth/logout`, `GET /authz/check`, `GET /members`, `GET /context`, `GET /projects/by-code/{code}`, `PUT /projects/{projectCode}`, `DELETE /projects/{projectCode}`
- `com/dwai/platform/internal/ServiceRegistry.java` — ServiceRegistry [组件] 每个产品只留最新一条登记（库 + 内存）。

## meta

- `com/dwai/platform/meta/AccessService.java` — AccessService [业务服务]
- `com/dwai/platform/meta/AiPromptService.java` — AiPromptService [业务服务]
- `com/dwai/platform/meta/KnowledgeImportParser.java` — KnowledgeImportParser
- `com/dwai/platform/meta/KnowledgeService.java` — KnowledgeService [业务服务]
- `com/dwai/platform/meta/MenuCandidateService.java` — MenuCandidateService [业务服务] 拉各服务的菜单候选，供平台管理员在「菜单管理」里勾选。
- `com/dwai/platform/meta/NavController.java` — NavController [HTTP 接口] 侧栏菜单树（消费面）：当前租户里，这个人该看到哪些入口。
- `com/dwai/platform/meta/NavNodeService.java` — NavNodeService [业务服务] 门户菜单树：管理面（平台管理员配树）与消费面（租户成员读树）。
- `com/dwai/platform/meta/PermWords.java` — PermWords 权限词的<b>形状</b>判定：严格两段式 域:动作，动作取自固定五档。
- `com/dwai/platform/meta/PlatformController.java` — PlatformController [HTTP 接口] · 前缀 `/api/platform`, `/api/v1/platform` → `GET /tenants`, `POST /tenants`, `PATCH /tenants/{id}`, `POST /tenants/{id}/reset-admin-password`, `GET /accounts`, `GET /users`, `POST /users`, `PATCH /users/{id}`, …(共 23 条)
- `com/dwai/platform/meta/PlatformService.java` — PlatformService [业务服务]
- `com/dwai/platform/meta/ProductCodes.java` — ProductCodes 允许登记的产品码，以及它与「租户许可模块名」两套词汇的关系。
- `com/dwai/platform/meta/ProductRoleService.java` — ProductRoleService [业务服务] 产品角色：把「哪个角色有哪些权限」从 Perms.java 的硬编码矩阵变成可管理的数据。
- `com/dwai/platform/meta/ProjectMemberController.java` — ProjectMemberController [HTTP 接口] 项目成员 —— 组织平台「项目壳」里「成员管理」页的后端。 · 前缀 `/api`, `/api/v1` → `GET /projects/{projectId}/members`, `PUT /projects/{projectId}/members/{userId}`, `DELETE /projects/{projectId}/members/{userId}`, `GET /projects/{projectId}/member-roles`
- `com/dwai/platform/meta/ProjectService.java` — ProjectService [业务服务]
- `com/dwai/platform/meta/ServiceCatalogController.java` — ServiceCatalogController [HTTP 接口] 产品服务目录（消费面）：当前租户开通了哪些产品、各自的前端地址在哪。
- `com/dwai/platform/meta/TenantAdminController.java` — TenantAdminController [HTTP 接口] · 前缀 `/api/tenants/{id}`, `/api/v1/tenants/{id}` → `GET /users`, `POST /users`, `PATCH /users/{userId}`, `DELETE /users/{userId}`, `GET /projects`, `POST /projects`, `PATCH /projects/{projectId}`, `DELETE /projects/{projectId}`, …(共 23 条)
- `com/dwai/platform/meta/TenantAdminService.java` — TenantAdminService [业务服务]

## meta/entity

- `com/dwai/platform/meta/entity/NavNodeEntity.java` — NavNodeEntity [实体] 侧栏菜单树上的一个节点（见 V23__nav_nodes.sql）。
- `com/dwai/platform/meta/entity/ProductRoleEntity.java` — ProductRoleEntity [实体] 一个产品的角色定义（见 V20__product_roles.sql）。
- `com/dwai/platform/meta/entity/ProductRolePermEntity.java` — ProductRolePermEntity [实体] 角色到权限词的关联行（见 V20__product_roles.sql 的 product_role_perms）。
- `com/dwai/platform/meta/entity/ServiceRegistryEntity.java` — ServiceRegistryEntity [实体]

## meta/mapper

- `com/dwai/platform/meta/mapper/NavNodeMapper.java` — NavNodeMapper [数据访问]
- `com/dwai/platform/meta/mapper/ProductRoleMapper.java` — ProductRoleMapper [数据访问]
- `com/dwai/platform/meta/mapper/ProductRolePermMapper.java` — ProductRolePermMapper [数据访问] 只走 wrapper 查询的 mapper —— 本表主键是复合的，selectById 语义不对。
- `com/dwai/platform/meta/mapper/ServiceRegistryMapper.java` — ServiceRegistryMapper [数据访问]

## scheduler

- `com/dwai/platform/scheduler/DolphinSchedulerClient.java` — DolphinSchedulerClient [组件]

## web

- `com/dwai/platform/web/ApiExceptionHandler.java` — ApiExceptionHandler
- `com/dwai/platform/web/RuntimeController.java` — RuntimeController [HTTP 接口] · `GET /api/runtime`, `GET /api/v1/manifest`
- `com/dwai/platform/web/SessionController.java` — SessionController [HTTP 接口] · 前缀 `/api`, `/api/v1` → `GET /health`, `GET /session`, `GET /tenants`, `GET /projects`
- `com/dwai/platform/web/SpaIndexController.java` — SpaIndexController [HTTP 接口] · `GET /`, `GET /login`
- `com/dwai/platform/web/WebConfig.java` — WebConfig [装配]

