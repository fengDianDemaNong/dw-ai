# dw-lineage（数据地图 · 后端）

> 由 `bin/gen-index.sh` 于 2026-09-27 18:15:33 生成（HEAD `04d51fd`）。**不要手工编辑**，改完代码重跑脚本即可。
> 共 192 个类。**路径 = 源码根 `dw-lineage/api/src/main/java/` + 下表路径**；分组标题是包名（已省略 `com/dwai/platform/` 这类公共前缀）。测试清单见 [tests.md](tests.md)。


## (根包)

- `com/dwai/lineage/Main.java` — Main [启动类] · `main`

## auth

- `com/dwai/lineage/auth/CurrentLocalUser.java` — CurrentLocalUser [组件] 「当前登录者是谁」的唯一解析入口。
- `com/dwai/lineage/auth/LocalAdminSeedRunner.java` — LocalAdminSeedRunner [组件] standard 模式空库时补一个初始管理员。
- `com/dwai/lineage/auth/LocalAuthController.java` — LocalAuthController [HTTP 接口] standard 模式本地认证端点：/api/auth/*。 · 前缀 `/api/auth`, `/api/v1/auth` → `GET /config`, `POST /login`, `POST /refresh`, `POST /logout`, `GET /me`, `PUT /profile`, `PUT /password`
- `com/dwai/lineage/auth/LocalAuthModels.java` — LocalAuthModels standard 模式本地认证的请求 / 响应体。
- `com/dwai/lineage/auth/LocalAuthService.java` — LocalAuthService [业务服务] standard 模式的本地认证：登录 / 刷新 / 登出。
- `com/dwai/lineage/auth/LocalJwtIssuer.java` — LocalJwtIssuer [组件] standard 模式本地 JWT 签发器（HS256，共享密钥）。
- `com/dwai/lineage/auth/LocalTokenEpoch.java` — LocalTokenEpoch [组件] 进程启动世代，写入 standard 模式本地签发的 access JWT。
- `com/dwai/lineage/auth/LocalUserController.java` — LocalUserController [HTTP 接口] 本地账号管理面（standard 模式）。 · 前缀 `/api/users`, `/api/v1/users` → `GET /{id}`, `PUT /{id}`, `PUT /{id}/status`, `PUT /{id}/password`, `DELETE /{id}`
- `com/dwai/lineage/auth/LocalUserService.java` — LocalUserService [业务服务] 本地账号管理（standard 模式）。

## cli

- `com/dwai/lineage/cli/ConfLoader.java` — ConfLoader 从 conf/application.yml 读取数据库配置，供命令行工具复用。
- `com/dwai/lineage/cli/SqlCli.java` — SqlCli 内置 SQL 命令行工具，用法与 beeline / trino cli 类似。 · `main`

## conf

- `com/dwai/lineage/conf/DataSourceConfig.java` — DataSourceConfig [启动期处理] 按 {@link DatabaseProperties#getType()} 组装数据源。
- `com/dwai/lineage/conf/DatabaseProperties.java` — DatabaseProperties [配置绑定] 面向部署人员的数据库配置。 · `@ConfigurationProperties(database)`
- `com/dwai/lineage/conf/FlywayLocationEnvironmentPostProcessor.java` — FlywayLocationEnvironmentPostProcessor [启动期处理] 按 database.type 把 Flyway 脚本目录指到对应方言， 与 dw-org / dw-model 的 MetaDbEnvironmentPostProcessor 做法一致。
- `com/dwai/lineage/conf/GravitinoConfig.java` — GravitinoConfig [配置绑定] · `@ConfigurationProperties(gravitino)`
- `com/dwai/lineage/conf/LineageProperties.java` — LineageProperties [配置绑定] · `@ConfigurationProperties(lineage)`
- `com/dwai/lineage/conf/OpenApiConfig.java` — OpenApiConfig [装配] 接口文档配置，访问 /swagger-ui.html 或 /swagger-ui/index.html。
- `com/dwai/lineage/conf/SecurityConfig.java` — SecurityConfig [装配] 三种运行模式下的身份认证。
- `com/dwai/lineage/conf/WebConfig.java` — WebConfig [装配] CORS 与租户拦截器配置。
- `com/dwai/lineage/conf/WebStaticConfig.java` — WebStaticConfig [装配] 由后端直接托管前端静态资源，安装包因此只需启动一个进程，不必额外部署 nginx。

## controller

- `com/dwai/lineage/controller/CatalogController.java` — CatalogController [HTTP 接口] 已保存血缘的查询：表基础信息、血缘关系、全局搜索三个页面用。 · 前缀 `/api` → `GET /catalog/schemas`, `GET /catalog/catalogs`, `GET /catalog/tables`, `GET /catalog/tables/{id}`, `GET /catalog/tables/{id}/columns`, `GET /catalog/tables/{id}/upstream`, `GET /catalog/tables/{id}/referenced`, `GET /catalog/columns/{id}/upstream`, …(共 16 条)
- `com/dwai/lineage/controller/DataCatalogController.java` — DataCatalogController [HTTP 接口] 数据目录与临时库表规则的配置接口。 · 前缀 `/api/data-catalogs` → `PUT /{id}`, `POST /{id}/default`, `DELETE /{id}`, `GET /temp-rules`, `POST /temp-rules`, `PUT /temp-rules/{id}`, `DELETE /temp-rules/{id}`, `GET /temp-rules/test`
- `com/dwai/lineage/controller/GravitinoMetadataController.java` — GravitinoMetadataController [HTTP 接口] @CrossOrigin(origins = "*") · 前缀 `/api/mate` → `GET /matelakes`, `GET /catalogs`, `POST /schemas`, `POST /lineage/analyze`
- `com/dwai/lineage/controller/InternalProjectController.java` — InternalProjectController [HTTP 接口] · 前缀 `/internal/v1` → `PUT /projects/{projectId}`, `DELETE /projects/{projectId}`
- `com/dwai/lineage/controller/LineageController.java` — LineageController [HTTP 接口] · 前缀 `/api` → `GET /dbType`, `GET /dialects`, `POST /lineage/analyze`, `POST /lineage/table`, `POST /sql/validate`, `GET /sql/keywords`
- `com/dwai/lineage/controller/MetaCatalogController.java` — MetaCatalogController [HTTP 接口] 元数据目录。 · 前缀 `/api/meta` → `GET /schemas`, `GET /catalogs`, `GET /tables`, `GET /tables/{id}`, `GET /tables/{id}/columns`, `POST /ddl`, `GET /sync/browse`, `GET /sync/table`, …(共 16 条)
- `com/dwai/lineage/controller/MetadataSourceController.java` — MetadataSourceController [HTTP 接口] 元数据服务配置。 · 前缀 `/api/metadata-sources` → `GET /capabilities`, `PUT /{id}`, `DELETE /{id}`, `POST /test`
- `com/dwai/lineage/controller/RuntimeController.java` — RuntimeController [HTTP 接口] · 前缀 `/api`, `/api/v1` → `GET /runtime`, `GET /manifest`
- `com/dwai/lineage/controller/StatsController.java` — StatsController [HTTP 接口] 概览页的统计。 · 前缀 `/api/stats` → `GET /project`, `GET /tenant`
- `com/dwai/lineage/controller/TenantAdminController.java` — TenantAdminController [HTTP 接口] 租户与项目的管理面。 · 前缀 `/api` → `GET /context`, `GET /tenants`, `POST /tenants`, `PUT /tenants/{id}`, `DELETE /tenants/{id}`, `GET /tenants/{tenantId}/projects`, `POST /tenants/{tenantId}/projects`, `PUT /tenants/{tenantId}/projects/{id}`, …(共 9 条)

## dto

- `com/dwai/lineage/dto/ActiveContextResponse.java` — ActiveContextResponse 当前请求实际生效的租户与项目。
- `com/dwai/lineage/dto/CatalogColumnResponse.java` — CatalogColumnResponse 血缘目录里的一个字段。
- `com/dwai/lineage/dto/CatalogSearchHit.java` — CatalogSearchHit 全局搜索的一条命中。
- `com/dwai/lineage/dto/CatalogStats.java` — CatalogStats 概览首页的统计数字，一次请求给全。
- `com/dwai/lineage/dto/CatalogTableResponse.java` — CatalogTableResponse 血缘目录里的一张表。
- `com/dwai/lineage/dto/DataCatalogRequest.java` — DataCatalogRequest 新建 / 更新数据目录。
- `com/dwai/lineage/dto/DataCatalogResponse.java` — DataCatalogResponse 数据目录，带该目录下已登记的表数量。
- `com/dwai/lineage/dto/DayCount.java` — DayCount 趋势图的一个桶：某一天的解析次数。
- `com/dwai/lineage/dto/DialectInfo.java` — DialectInfo 方言及其能力，供前端决定是否提示用户。
- `com/dwai/lineage/dto/ErrorResponse.java` — ErrorResponse 统一错误响应。
- `com/dwai/lineage/dto/FailedStatement.java` — FailedStatement 解析失败的单条语句。
- `com/dwai/lineage/dto/LineageAnalyzeRequest.java` — LineageAnalyzeRequest 血缘解析请求。
- `com/dwai/lineage/dto/LineageGraph.java` — LineageGraph 血缘图，接口的响应体。
- `com/dwai/lineage/dto/LineageSaveRequest.java` — LineageSaveRequest 解析并保存血缘。
- `com/dwai/lineage/dto/LineageSaveResponse.java` — LineageSaveResponse 保存结果。
- `com/dwai/lineage/dto/LineageVersionResponse.java` — LineageVersionResponse 一张表的一个血缘版本，供页面上的版本下拉使用。
- `com/dwai/lineage/dto/LocalUserPasswordRequest.java` — LocalUserPasswordRequest 管理员给别人重置密码。
- `com/dwai/lineage/dto/LocalUserRequest.java` — LocalUserRequest 新建 / 更新本地账号（standard 模式）。
- `com/dwai/lineage/dto/LocalUserResponse.java` — LocalUserResponse 本地账号（standard 模式）对外的形态。
- `com/dwai/lineage/dto/LocalUserStatusRequest.java` — LocalUserStatusRequest 启用 / 停用本地账号。
- `com/dwai/lineage/dto/MetaColumnResponse.java` — MetaColumnResponse 元数据目录里的一个字段。
- `com/dwai/lineage/dto/MetaColumnUpdateRequest.java` — MetaColumnUpdateRequest 手工修改字段的描述属性。
- `com/dwai/lineage/dto/MetaDdlImportRequest.java` — MetaDdlImportRequest 贴建表语句导入元数据。
- `com/dwai/lineage/dto/MetaSyncRequest.java` — MetaSyncRequest 从外部元数据服务同步表结构。
- `com/dwai/lineage/dto/MetaSyncResult.java` — MetaSyncResult 同步结果。
- `com/dwai/lineage/dto/MetaTableResponse.java` — MetaTableResponse 元数据目录里的一张表。
- `com/dwai/lineage/dto/MetaTableUpdateRequest.java` — MetaTableUpdateRequest 手工修改表的描述属性。
- `com/dwai/lineage/dto/MetadataSourceRequest.java` — MetadataSourceRequest 新建/更新元数据服务配置。
- `com/dwai/lineage/dto/MetadataSourceResponse.java` — MetadataSourceResponse 元数据服务配置的对外表示。
- `com/dwai/lineage/dto/MetadataSourceTestRequest.java` — MetadataSourceTestRequest 测试连接。
- `com/dwai/lineage/dto/MetadataSourceTestResponse.java` — MetadataSourceTestResponse 测试连接的结果。
- `com/dwai/lineage/dto/PageResponse.java` — PageResponse 通用分页结果。
- `com/dwai/lineage/dto/ProjectRequest.java` — ProjectRequest 新建 / 更新项目。
- `com/dwai/lineage/dto/ProjectResponse.java` — ProjectResponse
- `com/dwai/lineage/dto/ProjectStats.java` — ProjectStats 概览页「当前项目」口径的统计，一次请求给全。
- `com/dwai/lineage/dto/RemoteTableDetail.java` — RemoteTableDetail 从外部元数据服务<b>只读</b>取到的一张表的结构。
- `com/dwai/lineage/dto/SqlValidateRequest.java` — SqlValidateRequest SQL 语法校验请求。
- `com/dwai/lineage/dto/SqlValidateResponse.java` — SqlValidateResponse SQL 语法校验结果。
- `com/dwai/lineage/dto/StatCount.java` — StatCount 分布 / 计数条的一行：一个名字配一个计数。
- `com/dwai/lineage/dto/SyncJobResponse.java` — SyncJobResponse 导入任务的进度与结果，供页面轮询。
- `com/dwai/lineage/dto/TableLineageRequest.java` — TableLineageRequest 表级血缘请求。
- `com/dwai/lineage/dto/TableLineageResponse.java` — TableLineageResponse 表级血缘结果。
- `com/dwai/lineage/dto/TempRuleRequest.java` — TempRuleRequest 新建 / 更新临时库表规则。
- `com/dwai/lineage/dto/TempRuleResponse.java` — TempRuleResponse
- `com/dwai/lineage/dto/TempRuleTestResponse.java` — TempRuleTestResponse 规则试算结果。
- `com/dwai/lineage/dto/TenantRequest.java` — TenantRequest 新建 / 更新租户。
- `com/dwai/lineage/dto/TenantResponse.java` — TenantResponse 租户，内嵌其下的项目列表。
- `com/dwai/lineage/dto/TenantStats.java` — TenantStats 概览页「本租户」口径的统计：横跨该租户下的全部项目。

## enums

- `com/dwai/lineage/enums/DatabaseTypeEnum.java` — DatabaseTypeEnum 方言注册表：每种方言对应的解析器，以及它的能力边界。
- `com/dwai/lineage/enums/MatchType.java` — MatchType 临时库/表规则的匹配方式。
- `com/dwai/lineage/enums/MetaSource.java` — MetaSource 一行元数据是怎么来的，对应 meta_table.source / meta_column.source。
- `com/dwai/lineage/enums/MetadataSourceType.java` — MetadataSourceType 元数据服务类型，对应 metadata_source.type。
- `com/dwai/lineage/enums/SyncScope.java` — SyncScope 元数据导入的范围。
- `com/dwai/lineage/enums/SyncStatus.java` — SyncStatus
- `com/dwai/lineage/enums/TempRuleTarget.java` — TempRuleTarget

## exception

- `com/dwai/lineage/exception/GlobalExceptionHandler.java` — GlobalExceptionHandler
- `com/dwai/lineage/exception/MetadataConfigException.java` — MetadataConfigException 元数据服务的<b>配置</b>问题，不是服务故障，也不是用户输入错误。
- `com/dwai/lineage/exception/ResourceInUseException.java` — ResourceInUseException 目标还被数据引用着，因此拒绝删除。
- `com/dwai/lineage/exception/SqlParseException.java` — SqlParseException

## internal

- `com/dwai/lineage/internal/OrgClient.java` — OrgClient [组件]
- `com/dwai/lineage/internal/OrgProjectPuller.java` — OrgProjectPuller [组件] 组织平台 → 本模块的<b>项目镜像</b>按需拉取。

## persistence

- `com/dwai/lineage/persistence/DataCatalogRepository.java` — DataCatalogRepository 数据目录（data_catalog）与临时库表规则（temp_rule）的读写。
- `com/dwai/lineage/persistence/DataCatalogRow.java` — DataCatalogRow 本地元数据的一个数据目录。
- `com/dwai/lineage/persistence/JdbcDataCatalogRepository.java` — JdbcDataCatalogRepository
- `com/dwai/lineage/persistence/JdbcLineageCatalogRepository.java` — JdbcLineageCatalogRepository 血缘目录的 JdbcTemplate 实现。
- `com/dwai/lineage/persistence/JdbcLineageRepository.java` — JdbcLineageRepository 基于 JdbcTemplate 的血缘版本与边的持久化。
- `com/dwai/lineage/persistence/JdbcLocalUserRepository.java` — JdbcLocalUserRepository 基于 JdbcTemplate 的本地账号/刷新令牌存取（standard 模式认证）。
- `com/dwai/lineage/persistence/JdbcMetaCatalogRepository.java` — JdbcMetaCatalogRepository 元数据目录的 JdbcTemplate 实现。
- `com/dwai/lineage/persistence/JdbcMetadataSourceRepository.java` — JdbcMetadataSourceRepository 基于 JdbcTemplate 的元数据服务配置存取。
- `com/dwai/lineage/persistence/JdbcStatsRepository.java` — JdbcStatsRepository 概览统计的 JdbcTemplate 实现。
- `com/dwai/lineage/persistence/JdbcSyncJobRepository.java` — JdbcSyncJobRepository 导入任务的存取。
- `com/dwai/lineage/persistence/JdbcTenantAdminRepository.java` — JdbcTenantAdminRepository 基于 JdbcTemplate 的租户/项目管理面存取。
- `com/dwai/lineage/persistence/LineageCatalogRepository.java` — LineageCatalogRepository 血缘侧的表/字段目录。
- `com/dwai/lineage/persistence/LineageColumnRow.java` — LineageColumnRow 血缘目录中的一个字段。
- `com/dwai/lineage/persistence/LineageEdgeRow.java` — LineageEdgeRow 图遍历返回的一条边。
- `com/dwai/lineage/persistence/LineageRepository.java` — LineageRepository 血缘版本与边的持久化。
- `com/dwai/lineage/persistence/LineageTableRow.java` — LineageTableRow 血缘目录中的一张表。
- `com/dwai/lineage/persistence/LineageVersionRow.java` — LineageVersionRow 一张目标表的一个血缘版本。
- `com/dwai/lineage/persistence/LocalUserRepository.java` — LocalUserRepository standard 模式的本地账号与刷新令牌存取。
- `com/dwai/lineage/persistence/LocalUserRow.java` — LocalUserRow 本地账号（standard 模式的认证主体）。
- `com/dwai/lineage/persistence/MetaCatalogRepository.java` — MetaCatalogRepository 元数据目录的读写。
- `com/dwai/lineage/persistence/MetaColumnRow.java` — MetaColumnRow 元数据目录里的一个字段。
- `com/dwai/lineage/persistence/MetaTableRow.java` — MetaTableRow 元数据目录里的一张表。
- `com/dwai/lineage/persistence/MetadataSourceRepository.java` — MetadataSourceRepository 元数据服务配置的读写。
- `com/dwai/lineage/persistence/MetadataSourceRow.java` — MetadataSourceRow metadata_source 表的一行。
- `com/dwai/lineage/persistence/ProjectRow.java` — ProjectRow project 表的一行。
- `com/dwai/lineage/persistence/RefreshTokenRow.java` — RefreshTokenRow
- `com/dwai/lineage/persistence/StatsLimits.java` — StatsLimits 概览统计的窗口期与条数。
- `com/dwai/lineage/persistence/StatsRepository.java` — StatsRepository 概览页两个口径的统计。
- `com/dwai/lineage/persistence/SyncJobRepository.java` — SyncJobRepository 导入任务的存取。
- `com/dwai/lineage/persistence/SyncJobRow.java` — SyncJobRow 一次元数据导入任务。
- `com/dwai/lineage/persistence/TempRuleRow.java` — TempRuleRow 一条临时库/表规则。
- `com/dwai/lineage/persistence/TenantAdminRepository.java` — TenantAdminRepository 租户与项目自身的存取 —— 也就是「管理面」。
- `com/dwai/lineage/persistence/TenantRow.java` — TenantRow tenant 表的一行。

## service

- `com/dwai/lineage/service/AnalyzedLineage.java` — AnalyzedLineage 一次解析的完整产出：给前端看的图 + 给保存用的描述快照。
- `com/dwai/lineage/service/CatalogService.java` — CatalogService 血缘目录的查询：表基础信息页与全局搜索页用。
- `com/dwai/lineage/service/DataCatalogService.java` — DataCatalogService 数据目录与临时库表规则的管理。
- `com/dwai/lineage/service/GravitinoMetadataServic.java` — GravitinoMetadataServic Gravitino 元数据浏览与解析。
- `com/dwai/lineage/service/LineageAuthz.java` — LineageAuthz [组件] 写操作的跨服务鉴权兜底。
- `com/dwai/lineage/service/LineageGraphService.java` — LineageGraphService 已保存血缘的查询与版本管理。
- `com/dwai/lineage/service/LineageService.java` — LineageService
- `com/dwai/lineage/service/MetaCatalogService.java` — MetaCatalogService
- `com/dwai/lineage/service/MetaSyncService.java` — MetaSyncService 把外部元数据服务（Gravitino / dbx）里的表结构同步进本地元数据目录。
- `com/dwai/lineage/service/MetadataSourceService.java` — MetadataSourceService 元数据服务配置的增删改查与连通性测试。
- `com/dwai/lineage/service/SqlParseExecutor.java` — SqlParseExecutor [组件] 在受控线程中执行 SQL 解析，提供两层保护。
- `com/dwai/lineage/service/SqlSyntaxService.java` — SqlSyntaxService [业务服务] SQL 语法校验与关键字，供编辑器做实时提示。
- `com/dwai/lineage/service/StatsService.java` — StatsService 概览页的统计，分项目级与租户级两个口径。
- `com/dwai/lineage/service/TableLineageService.java` — TableLineageService [业务服务] 表级血缘：只看「哪张表写入、来自哪些表」，不下钻到字段。
- `com/dwai/lineage/service/TenantAdminService.java` — TenantAdminService 租户与项目的管理面。

## service/impl

- `com/dwai/lineage/service/impl/CatalogServiceImpl.java` — CatalogServiceImpl [业务服务] 血缘目录查询。
- `com/dwai/lineage/service/impl/DataCatalogServiceImpl.java` — DataCatalogServiceImpl [业务服务]
- `com/dwai/lineage/service/impl/GravitinoMetadataServiceImp.java` — GravitinoMetadataServiceImp [业务服务] Gravitino 元数据浏览与解析。
- `com/dwai/lineage/service/impl/LineageGraphServiceImpl.java` — LineageGraphServiceImpl [业务服务]
- `com/dwai/lineage/service/impl/LineageServiceImpl.java` — LineageServiceImpl [业务服务]
- `com/dwai/lineage/service/impl/MetaCatalogServiceImpl.java` — MetaCatalogServiceImpl [业务服务]
- `com/dwai/lineage/service/impl/MetaSyncServiceImpl.java` — MetaSyncServiceImpl [业务服务] 元数据同步。
- `com/dwai/lineage/service/impl/MetadataSourceServiceImpl.java` — MetadataSourceServiceImpl [业务服务] 元数据服务配置的实现。
- `com/dwai/lineage/service/impl/StatsServiceImpl.java` — StatsServiceImpl [业务服务] 概览统计。
- `com/dwai/lineage/service/impl/SyncJobRunner.java` — SyncJobRunner [组件] 导入任务的执行器。
- `com/dwai/lineage/service/impl/TenantAdminServiceImpl.java` — TenantAdminServiceImpl [业务服务]

## service/metadata

- `com/dwai/lineage/service/metadata/CatalogPolicy.java` — CatalogPolicy 一个项目的数据目录策略：默认目录名 + 临时库表规则。
- `com/dwai/lineage/service/metadata/CredentialCipher.java` — CredentialCipher [组件] 元数据服务凭据的加解密。
- `com/dwai/lineage/service/metadata/DdlCatalogExtractor.java` — DdlCatalogExtractor 从建表语句抽取元数据目录条目。
- `com/dwai/lineage/service/metadata/GravitinoClientRegistry.java` — GravitinoClientRegistry [组件] 按地址缓存 GravitinoAdminClient。
- `com/dwai/lineage/service/metadata/GravitinoExecutor.java` — GravitinoExecutor [组件] com.dwai.lineage.service.metadata.provider.GravitinoMetadataProvider 逐表并发加载用的线程池。
- `com/dwai/lineage/service/metadata/JdbcQueryMetadataService.java` — JdbcQueryMetadataService
- `com/dwai/lineage/service/metadata/LineageAnalysisPipeline.java` — LineageAnalysisPipeline 血缘解析主流程，与元数据来源解耦。
- `com/dwai/lineage/service/metadata/MetaNames.java` — MetaNames 元数据目录里表/字段全名的规范化。
- `com/dwai/lineage/service/metadata/MetadataServiceFactory.java` — MetadataServiceFactory [组件] 按方言拆分 SQL，并把血缘解析主流程与元数据来源组装起来。
- `com/dwai/lineage/service/metadata/ParseCancellation.java` — ParseCancellation 给 ANTLR 解析装一个可中断的检查点。
- `com/dwai/lineage/service/metadata/RegexGuard.java` — RegexGuard 给<b>用户自己写的</b>正则加一道保险，防止灾难性回溯把线程钉死。
- `com/dwai/lineage/service/metadata/SourceExtraConfig.java` — SourceExtraConfig metadata_source.extra_config 里那段 JSON 的解析结果。
- `com/dwai/lineage/service/metadata/TableNameNormalizer.java` — TableNameNormalizer 把解析出来的表名 / 字段名补齐成带数据目录的全名。
- `com/dwai/lineage/service/metadata/TempTableFilter.java` — TempTableFilter 把临时表从血缘边表里<b>穿透掉</b>。
- `com/dwai/lineage/service/metadata/TempTableMatcher.java` — TempTableMatcher 按配置的规则判断一张表是不是临时表。

## service/metadata/dbx

- `com/dwai/lineage/service/metadata/dbx/DbxClient.java` — DbxClient dbx Web API 客户端。
- `com/dwai/lineage/service/metadata/dbx/DbxClientFactory.java` — DbxClientFactory [组件] 按地址与凭据造 DbxClient。
- `com/dwai/lineage/service/metadata/dbx/DbxColumn.java` — DbxColumn dbx /api/schema/columns 返回的一个字段（结构化）。
- `com/dwai/lineage/service/metadata/dbx/DbxConnectionConfig.java` — DbxConnectionConfig dbx 的一个数据库连接配置，对应 POST /api/connection/connect 请求体里的 config。
- `com/dwai/lineage/service/metadata/dbx/DbxConnectionSummary.java` — DbxConnectionSummary dbx 已保存连接的<b>脱敏</b>摘要。
- `com/dwai/lineage/service/metadata/dbx/DbxException.java` — DbxException dbx 调用失败。

## service/metadata/gravitino

- `com/dwai/lineage/service/metadata/gravitino/GravitinoColumn.java` — GravitinoColumn Gravitino 中的一个字段（结构化）。
- `com/dwai/lineage/service/metadata/gravitino/GravitinoTableSchema.java` — GravitinoTableSchema 从 Gravitino 读到的一张表的完整结构，供同步进元数据目录使用。

## service/metadata/provider

- `com/dwai/lineage/service/metadata/provider/CatalogMetadataProvider.java` — CatalogMetadataProvider 从我们自己维护的元数据目录（meta_table / meta_column）读表结构。
- `com/dwai/lineage/service/metadata/provider/ColumnDescriptor.java` — ColumnDescriptor 一个字段的描述属性快照。
- `com/dwai/lineage/service/metadata/provider/CompositeMetadataProvider.java` — CompositeMetadataProvider 按优先级串联多个 provider：前一个解析不到的表，才交给后一个。
- `com/dwai/lineage/service/metadata/provider/DbxMetadataProvider.java` — DbxMetadataProvider 从 dbx 读取表结构。
- `com/dwai/lineage/service/metadata/provider/DdlMetadataProvider.java` — DdlMetadataProvider 从 SQL 脚本自带的 CREATE TABLE 语句提取表结构。
- `com/dwai/lineage/service/metadata/provider/GravitinoMetadataProvider.java` — GravitinoMetadataProvider 从 Gravitino 读取表结构。
- `com/dwai/lineage/service/metadata/provider/InferredMetadataProvider.java` — InferredMetadataProvider 从 SQL 语句本身推断表结构：SQL 里显式引用了哪些列，就认为表至少有这些列。
- `com/dwai/lineage/service/metadata/provider/MetadataProvider.java` — MetadataProvider 表结构元数据来源。
- `com/dwai/lineage/service/metadata/provider/MetadataResolution.java` — MetadataResolution 一次元数据解析的结果。
- `com/dwai/lineage/service/metadata/provider/SchemaTables.java` — SchemaTables 构造 SchemaTable 的公共入口。
- `com/dwai/lineage/service/metadata/provider/TableDescriptor.java` — TableDescriptor 一张表的<b>描述属性</b>快照：中文名、备注、表类型，以及各字段的类型与中文名。

## tenant

- `com/dwai/lineage/tenant/LineageContext.java` — LineageContext 租户 + 项目上下文。
- `com/dwai/lineage/tenant/TenantContextHolder.java` — TenantContextHolder 当前请求的租户上下文。
- `com/dwai/lineage/tenant/TenantInterceptor.java` — TenantInterceptor [组件] 在 HTTP 层解析租户上下文，塞进 TenantContextHolder。

## util

- `com/dwai/lineage/util/FileUtils.java` — FileUtils 文件操作工具类
- `com/dwai/lineage/util/JsonUtils.java` — JsonUtils
- `com/dwai/lineage/util/SQLLineageMerger.java` — SQLLineageMerger 把 sqlflow 产出的多条 Output JSON 合并成前端可直接渲染的血缘图。
- `com/dwai/lineage/util/SqlUtils.java` — SqlUtils

