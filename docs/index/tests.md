# 测试清单

> 由 `bin/gen-index.sh` 于 2026-09-29 11:57:33 生成（HEAD `78c8f30`）。**不要手工编辑**，改完代码重跑脚本即可。
> 只列类名与一句话职责；具体断言请打开文件。**路径 = 小节标题里的模块名 + `/` + 下表路径**。跑测试见各模块 `api/pom.xml`。


## dw-org（17 个）

- `api/src/test/java/com/dwai/platform/AppearanceShellContractTest.java`
- `api/src/test/java/com/dwai/platform/AuthSmokeTest.java`
- `api/src/test/java/com/dwai/platform/ComputeContractTest.java`
- `api/src/test/java/com/dwai/platform/CrossServiceDesignGuardTest.java` — 把技术方案 §2 的三条<b>禁止项</b>变成源码级的守卫。
- `api/src/test/java/com/dwai/platform/InternalContractTest.java`
- `api/src/test/java/com/dwai/platform/InternalModuleTokenTest.java`
- `api/src/test/java/com/dwai/platform/MenuCandidateContractTest.java`
- `api/src/test/java/com/dwai/platform/ModulePolicyContractTest.java`
- `api/src/test/java/com/dwai/platform/NavEntryExternalTest.java`
- `api/src/test/java/com/dwai/platform/NavNodeTest.java`
- `api/src/test/java/com/dwai/platform/NavTreeMountTest.java`
- `api/src/test/java/com/dwai/platform/PermWordsTest.java` — 权限词的<b>形状</b>规则，以及「三份词表不许漂移」的守卫。
- `api/src/test/java/com/dwai/platform/PlatformAdminContractTest.java`
- `api/src/test/java/com/dwai/platform/ProductRoleTest.java`
- `api/src/test/java/com/dwai/platform/ProjectMemberContractTest.java`
- `api/src/test/java/com/dwai/platform/RunModeSmokeTest.java` — dw-org 的运行模式护栏：<b>组织平台只有 multi 一种模式</b>。
- `api/src/test/java/com/dwai/platform/SchemaBootstrapTest.java`

## dw-model（7 个）

- `api/src/test/java/com/dwai/platform/AuthSmokeTest.java`
- `api/src/test/java/com/dwai/platform/InternalContractTest.java`
- `api/src/test/java/com/dwai/platform/InternalModuleTokenTest.java`
- `api/src/test/java/com/dwai/platform/MultiTenantIsolationTest.java`
- `api/src/test/java/com/dwai/platform/OrgClientTimeoutTest.java`
- `api/src/test/java/com/dwai/platform/RunModeSmokeTest.java` — 三模式冒烟：dw-model 在 <b>standalone / standard / multi</b> 三种启动模式下都必须能起来， 且关键行为符合设计（见 docs/tech/07-0.2.0.md §3.3、§4）。
- `api/src/test/java/com/dwai/platform/SchemaBootstrapTest.java`

## dw-lineage（73 个）

- `api/src/test/java/SQLLineageMergerTest.java`
- `api/src/test/java/TestSqlSplit.java`
- `api/src/test/java/auth/LocalUserLockoutGuardTest.java`
- `api/src/test/java/com/dwai/lineage/cli/SqlCliTest.java` — sql-cli 的纯逻辑部分。
- `api/src/test/java/conf/LineagePropertiesRunModeTest.java` — runMode() 归一化的取值表。
- `api/src/test/java/controller/DataCatalogApiTest.java`
- `api/src/test/java/controller/EmbedFramePolicyTest.java`
- `api/src/test/java/controller/EmbedFramePolicyUnsetTest.java`
- `api/src/test/java/controller/InternalModuleTokenTest.java` — 服务间接口（/internal/v1/**）的门禁：静态共享密钥。
- `api/src/test/java/controller/LocalAuthApiTest.java`
- `api/src/test/java/controller/LocalUserApiTest.java`
- `api/src/test/java/controller/MenuJsonContractTest.java` — 菜单候选（`ui/public/menu.json`）必须覆盖 GET /api/manifest 的 menus， 且同一页面的权限词要一致。
- `api/src/test/java/controller/RemoteMetaBrowseApiTest.java`
- `api/src/test/java/controller/RemoteMetaBrowseLiveIT.java`
- `api/src/test/java/controller/RunModeSmokeTest.java` — 三模式冒烟：数据地图（dw-lineage）在 <b>standalone / standard / multi</b> 下都必须能起来。
- `api/src/test/java/controller/StatsApiTest.java`
- `api/src/test/java/controller/SyncJobApiTest.java`
- `api/src/test/java/controller/TenantAdminApiTest.java`
- `api/src/test/java/controller/TenantHeaderContractTest.java`
- `api/src/test/java/controller/TenantIsolationApiTest.java`
- `api/src/test/java/dialect/DialectCapabilityTest.java` — 方言能力矩阵的回归测试。
- `api/src/test/java/gravitino/Test.java`
- `api/src/test/java/lineage/AbstractSqlLineageTest.java` — huaixin 2021/12/26 9:06 PM
- `api/src/test/java/lineage/DorisInitDdlAccuracyTest.java` — 用现网 dw/init 的 Doris 建表语句对照解析结果。
- `api/src/test/java/lineage/DorisOpsSqlAccuracyTest.java` — 用现网 dw/ops 的 Doris SQL 做一次解析对照，不作为默认 CI 门槛。
- `api/src/test/java/lineage/LineageEndToEndTest.java` — 端到端血缘解析：SQL 文本 -> superior-sql-parser -> sqlflow -> SQLLineageMerger。
- `api/src/test/java/lineage/SqlParseExecutorTest.java` — 解析执行器的保护行为：超时、栈溢出隔离、异常透传。
- `api/src/test/java/lineage/TableLineageAndSyntaxTest.java` — 表级血缘（T8）与 SQL 语法校验 / 关键字（T9）。
- `api/src/test/java/lineage/flink/FlinkMetadataService.java`
- `api/src/test/java/lineage/hive/HiveMetadataService.java`
- `api/src/test/java/lineage/hive/HiveSqlLineageTest.java`
- `api/src/test/java/lineage/hive/HiveSqlLineageTest2.java`
- `api/src/test/java/lineage/mysql/MySqlMetadataService.java`
- `api/src/test/java/lineage/presto/PrestoMetadataService.java` — huaixin 2021/12/25 6:13 PM
- `api/src/test/java/lineage/presto/PrestoSqlLineageTest.java` — huaixin 2021/12/18 11:13 PM
- `api/src/test/java/lineage/spark/SparkMetadataService.java` — huaixin 2021/12/25 6:13 PM
- `api/src/test/java/lineage/spark/SparkSqlLineageTest.java` — huaixin 2021/12/18 11:13 PM
- `api/src/test/java/lineage/spark/SparkSqlLineageTest1.java` — huaixin 2021/12/18 11:13 PM
- `api/src/test/java/metadata/CatalogMetadataProviderTest.java` — 本地元数据目录作为解析来源，以及<b>元数据与血缘的隔离</b>。
- `api/src/test/java/metadata/CatalogNamespaceTest.java` — 数据目录（catalog）参与表的唯一标识。
- `api/src/test/java/metadata/CredentialCipherTest.java`
- `api/src/test/java/metadata/DbxClientSecurityTest.java` — dbx 客户端的安全约束。
- `api/src/test/java/metadata/DbxMetadataProviderTest.java` — DbxMetadataProvider 对 com.dwai.lineage.service.metadata.provider.MetadataProvider 契约的遵守情况：批量入参、查不到进 unresolved 而不抛异常、以及分区列缺失的告警。
- `api/src/test/java/metadata/DdlCatalogExtractorTest.java` — 从建表语句抽取元数据目录条目。
- `api/src/test/java/metadata/MetadataProviderTest.java` — MetadataProvider SPI 的优先级串联与降级行为。
- `api/src/test/java/metadata/ParseCancellationTest.java` — 解析取消检查点。
- `api/src/test/java/metadata/ProviderSchemaTableLookupTest.java` — provider 产出的 SchemaTable 必须能被分析器按名字找回来。
- `api/src/test/java/metadata/RegexGuardTest.java` — 用户自定义正则的回溯保险。
- `api/src/test/java/metadata/TableDescriptorPropagationTest.java` — 描述属性能不能从元数据来源一路带出来。
- `api/src/test/java/metadata/TableNameNormalizerTest.java` — 表名 / 字段名补齐数据目录。
- `api/src/test/java/metadata/TempTableFilterTest.java` — 临时表的识别与穿透。
- `api/src/test/java/parser/FlinkSqlParserDmlTest.java`
- `api/src/test/java/parser/TestSuperior.java`
- `api/src/test/java/persistence/AbstractLineageRepositoryTest.java` — 血缘持久化的行为契约。
- `api/src/test/java/persistence/CatalogStatsAndLookupTest.java` — 概览统计与按全名批量查询。
- `api/src/test/java/persistence/FlywayFreshInstallTest.java`
- `api/src/test/java/persistence/H2LineageRepositoryTest.java`
- `api/src/test/java/persistence/MetaCatalogRepositoryTest.java` — 元数据目录的读写，重点是<b>手工内容的保护</b>与<b>跨租户隔离</b>。
- `api/src/test/java/persistence/MetadataSourceRepositoryTest.java` — 元数据服务配置的存取，含跨租户越权的负向用例。
- `api/src/test/java/persistence/MigrationDialectTest.java` — 建表脚本方言验证：三套 DDL 必须都能真实执行，并产出一致的表结构。
- `api/src/test/java/persistence/MigrationExternalDbIT.java` — MySQL / PostgreSQL 的建表脚本与递归 CTE 验证。
- `api/src/test/java/persistence/MySqlLineageRepositoryIT.java` — MySQL 后端。
- `api/src/test/java/persistence/PostgresLineageRepositoryIT.java` — PostgreSQL 后端。
- `api/src/test/java/persistence/StatsRepositoryTest.java` — 概览统计两个口径的聚合查询。
- `api/src/test/java/persistence/TenantAdminRepositoryTest.java` — 租户/项目管理面的存取。
- `api/src/test/java/persistence/TenantIsolationArchTest.java` — 架构约束：persistence 包里的每一条原生 SQL 都必须带 tenant_id 过滤。
- `api/src/test/java/persistence/TestDataScriptTest.java` — 测试数据脚本必须能在建好的库上跑通，并造出功能验证需要的那几种数据。
- `api/src/test/java/persistence/TestDataSources.java`
- `api/src/test/java/persistence/TestSchema.java` — 测试用的建库：执行的就是 Flyway 用的那套迁移脚本。
- `api/src/test/java/service/SyncJobRunnerTest.java` — 导入任务执行器的跨线程上下文传递。
- `api/src/test/java/support/AuthenticatedMockMvc.java` — 给测试里的<b>每个</b>请求默认挂上 standard 模式的 access 令牌。
- `api/src/test/java/support/DevJwt.java` — 造一个与组织（dw-org / dw-model）dev 模式<b>同形</b>的测试令牌。
- `api/src/test/java/util/SQLLineageMergerCorrectnessTest.java` — SQLLineageMerger 正确性回归测试。

