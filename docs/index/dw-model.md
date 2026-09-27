# dw-model（仓建设 · 后端）

> 由 `bin/gen-index.sh` 于 2026-09-27 18:15:33 生成（HEAD `04d51fd`）。**不要手工编辑**，改完代码重跑脚本即可。
> 共 54 个类。**路径 = 源码根 `dw-model/api/src/main/java/` + 下表路径**；分组标题是包名（已省略 `com/dwai/platform/` 这类公共前缀）。测试清单见 [tests.md](tests.md)。


## (根包)

- `com/dwai/platform/DwaiApplication.java` — DwaiApplication [启动类] · `main`
- `com/dwai/platform/DwaiProperties.java` — DwaiProperties [配置绑定] · `@ConfigurationProperties(dwai)`

## auth

- `com/dwai/platform/auth/AuthController.java` — AuthController [HTTP 接口] · 前缀 `/api/auth`, `/api/v1/auth` → `GET /config`, `POST /login`, `POST /refresh`, `POST /logout`, `GET /tenants`, `POST /select-tenant`, `POST /enter-tenant`, `GET /me`
- `com/dwai/platform/auth/AuthService.java` — AuthService [业务服务]
- `com/dwai/platform/auth/BootstrapAdminRunner.java` — BootstrapAdminRunner [组件]
- `com/dwai/platform/auth/JwtIssuer.java` — JwtIssuer [组件]
- `com/dwai/platform/auth/JwtSessionEpoch.java` — JwtSessionEpoch [组件]
- `com/dwai/platform/auth/LlmCrypto.java` — LlmCrypto [组件]
- `com/dwai/platform/auth/MeController.java` — MeController [HTTP 接口] · 前缀 `/api/me`, `/api/v1/me` → `PUT /password`
- `com/dwai/platform/auth/RefreshTokenService.java` — RefreshTokenService [业务服务]
- `com/dwai/platform/auth/SecurityConfig.java` — SecurityConfig [装配]
- `com/dwai/platform/auth/TenantContext.java` — TenantContext
- `com/dwai/platform/auth/TenantFilter.java` — TenantFilter
- `com/dwai/platform/auth/WarehouseLocalSeedRunner.java` — WarehouseLocalSeedRunner [组件] 仓建设独立 / 普通模式空库补一份本地租户。

## db

- `com/dwai/platform/db/MetaDbEnvironmentPostProcessor.java` — MetaDbEnvironmentPostProcessor [启动期处理]

## internal

- `com/dwai/platform/internal/OrgClient.java` — OrgClient [组件]
- `com/dwai/platform/internal/OrgProjectPuller.java` — OrgProjectPuller [组件] 组织平台 → 本模块的<b>项目镜像 + 许可</b>按需拉取。
- `com/dwai/platform/internal/WarehouseInternalController.java` — WarehouseInternalController [HTTP 接口] · 前缀 `/internal/v1` → `PUT /projects/{projectCode}`, `DELETE /projects/{projectCode}`

## meta

- `com/dwai/platform/meta/AccessService.java` — AccessService [业务服务]
- `com/dwai/platform/meta/AiPromptService.java` — AiPromptService [业务服务]
- `com/dwai/platform/meta/AiService.java` — AiService [业务服务]
- `com/dwai/platform/meta/KnowledgeImportParser.java` — KnowledgeImportParser
- `com/dwai/platform/meta/KnowledgeService.java` — KnowledgeService [业务服务]
- `com/dwai/platform/meta/MetaController.java` — MetaController [HTTP 接口] · 前缀 `/api`, `/api/v1` → `POST /projects`, `GET /projects/{projectId}`, `PATCH /projects/{projectId}`, `DELETE /projects/{projectId}`, `GET /projects/{projectId}/snapshot`, `GET /projects/{projectId}/members`, `PUT /projects/{projectId}/members/{userId}`, `DELETE /projects/{projectId}/members/{userId}`, …(共 45 条)
- `com/dwai/platform/meta/ProjectService.java` — ProjectService [业务服务]
- `com/dwai/platform/meta/SpecService.java` — SpecService [业务服务]
- `com/dwai/platform/meta/TableService.java` — TableService [业务服务]
- `com/dwai/platform/meta/TableVersionService.java` — TableVersionService [业务服务]

## meta/entity

- `com/dwai/platform/meta/entity/DataGradeEntity.java` — DataGradeEntity [实体]
- `com/dwai/platform/meta/entity/DomainEntity.java` — DomainEntity [实体]
- `com/dwai/platform/meta/entity/LayerRuleEntity.java` — LayerRuleEntity [实体]
- `com/dwai/platform/meta/entity/ModelingDraftEntity.java` — ModelingDraftEntity [实体]
- `com/dwai/platform/meta/entity/TableColumnEntity.java` — TableColumnEntity [实体]
- `com/dwai/platform/meta/entity/TableVersionEntity.java` — TableVersionEntity [实体]
- `com/dwai/platform/meta/entity/WarehouseTableEntity.java` — WarehouseTableEntity [实体]
- `com/dwai/platform/meta/entity/WordRootEntity.java` — WordRootEntity [实体]

## meta/mapper

- `com/dwai/platform/meta/mapper/DataGradeMapper.java` — DataGradeMapper [数据访问]
- `com/dwai/platform/meta/mapper/DomainMapper.java` — DomainMapper [数据访问]
- `com/dwai/platform/meta/mapper/LayerRuleMapper.java` — LayerRuleMapper [数据访问]
- `com/dwai/platform/meta/mapper/ModelingDraftMapper.java` — ModelingDraftMapper [数据访问]
- `com/dwai/platform/meta/mapper/TableColumnMapper.java` — TableColumnMapper [数据访问]
- `com/dwai/platform/meta/mapper/TableVersionMapper.java` — TableVersionMapper [数据访问]
- `com/dwai/platform/meta/mapper/WarehouseTableMapper.java` — WarehouseTableMapper [数据访问]
- `com/dwai/platform/meta/mapper/WordRootMapper.java` — WordRootMapper [数据访问]

## query

- `com/dwai/platform/query/QueryController.java` — QueryController [HTTP 接口] · 前缀 `/api/query`, `/api/v1/query` → `POST /preview`
- `com/dwai/platform/query/StarRocksExecutor.java` — StarRocksExecutor [组件]

## rules

- `com/dwai/platform/rules/RulesClient.java` — RulesClient [组件]

## scheduler

- `com/dwai/platform/scheduler/DolphinSchedulerClient.java` — DolphinSchedulerClient [组件]
- `com/dwai/platform/scheduler/JobController.java` — JobController [HTTP 接口] · 前缀 `/api/jobs`, `/api/v1/jobs` → `POST /publish`

## web

- `com/dwai/platform/web/ApiExceptionHandler.java` — ApiExceptionHandler
- `com/dwai/platform/web/RuntimeController.java` — RuntimeController [HTTP 接口] · `GET /api/runtime`, `GET /api/v1/manifest`
- `com/dwai/platform/web/SessionController.java` — SessionController [HTTP 接口] · 前缀 `/api`, `/api/v1` → `GET /health`, `GET /session`, `GET /tenants`, `GET /projects`
- `com/dwai/platform/web/SpaIndexController.java` — SpaIndexController [HTTP 接口] · `GET /`, `GET /login`
- `com/dwai/platform/web/WebConfig.java` — WebConfig [装配]

