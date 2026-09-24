# dw-org（组织平台 · 后端）

> 由 `bin/gen-index.sh` 于 2026-09-23 15:45:01 生成（HEAD `194259f`）。**不要手工编辑**，改完代码重跑脚本即可。
> 共 37 个类。**路径 = 源码根 `dw-org/api/src/main/java/` + 下表路径**；分组标题是包名（已省略 `com/dwai/platform/` 这类公共前缀）。测试清单见 [tests.md](tests.md)。


## (根包)

- `com/dwai/platform/DwaiApplication.java` — DwaiApplication [启动类] · `main`
- `com/dwai/platform/DwaiProperties.java` — DwaiProperties [配置绑定] · `@ConfigurationProperties(dwai)`
- `com/dwai/platform/SeedMain.java` — SeedMain · `main`

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

- `com/dwai/platform/db/MetaDb.java` — MetaDb
- `com/dwai/platform/db/MetaDbEnvironmentPostProcessor.java` — MetaDbEnvironmentPostProcessor [启动期处理]

## internal

- `com/dwai/platform/internal/InternalController.java` — InternalController [HTTP 接口] · 前缀 `/internal/v1` → `POST /auth/refresh`, `POST /auth/logout`, `POST /registry/heartbeat`, `GET /registry`, `GET /authz/check`, `GET /members`, `GET /context`, `GET /projects/{projectId}/bindings`, …(共 10 条)
- `com/dwai/platform/internal/ModuleSyncService.java` — ModuleSyncService [业务服务]
- `com/dwai/platform/internal/ServiceRegistry.java` — ServiceRegistry [组件]

## meta

- `com/dwai/platform/meta/AccessService.java` — AccessService [业务服务]
- `com/dwai/platform/meta/AiPromptService.java` — AiPromptService [业务服务]
- `com/dwai/platform/meta/KnowledgeImportParser.java` — KnowledgeImportParser
- `com/dwai/platform/meta/KnowledgeService.java` — KnowledgeService [业务服务]
- `com/dwai/platform/meta/PlatformController.java` — PlatformController [HTTP 接口] · 前缀 `/api/platform`, `/api/v1/platform` → `GET /tenants`, `POST /tenants`, `PATCH /tenants/{id}`, `POST /tenants/{id}/reset-admin-password`, `GET /accounts`, `GET /users`, `POST /users`, `PATCH /users/{id}`, …(共 13 条)
- `com/dwai/platform/meta/PlatformService.java` — PlatformService [业务服务]
- `com/dwai/platform/meta/ProjectService.java` — ProjectService [业务服务]
- `com/dwai/platform/meta/TenantAdminController.java` — TenantAdminController [HTTP 接口] · 前缀 `/api/tenants/{id}`, `/api/v1/tenants/{id}` → `GET /users`, `POST /users`, `PATCH /users/{userId}`, `DELETE /users/{userId}`, `GET /projects`, `POST /projects`, `PATCH /projects/{projectId}`, `DELETE /projects/{projectId}`, …(共 23 条)
- `com/dwai/platform/meta/TenantAdminService.java` — TenantAdminService [业务服务]

## meta/entity

- `com/dwai/platform/meta/entity/ServiceRegistryEntity.java` — ServiceRegistryEntity [实体]

## meta/mapper

- `com/dwai/platform/meta/mapper/ServiceRegistryMapper.java` — ServiceRegistryMapper [数据访问]

## scheduler

- `com/dwai/platform/scheduler/DolphinSchedulerClient.java` — DolphinSchedulerClient [组件]

## web

- `com/dwai/platform/web/ApiExceptionHandler.java` — ApiExceptionHandler
- `com/dwai/platform/web/RuntimeController.java` — RuntimeController [HTTP 接口] · `GET /api/runtime`, `GET /api/v1/manifest`
- `com/dwai/platform/web/SessionController.java` — SessionController [HTTP 接口] · 前缀 `/api`, `/api/v1` → `GET /health`, `GET /session`, `GET /tenants`, `GET /projects`
- `com/dwai/platform/web/SpaIndexController.java` — SpaIndexController [HTTP 接口] · `GET /`, `GET /login`
- `com/dwai/platform/web/WebConfig.java` — WebConfig [装配]

