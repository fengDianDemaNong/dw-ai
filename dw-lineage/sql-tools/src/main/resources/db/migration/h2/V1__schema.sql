-- SQL 血缘分析平台 · 表结构（H2 2.x（本地/嵌入式））
-- 结构版本：1.0.5
--
-- 全新安装执行本文件，再执行 02_init_data.sql。
-- 升级已有库请改用 upgrade/ 目录下的脚本，不要重复执行本文件。
--
-- 所有业务表都带 tenant_id + project_id，查询强制按租户过滤。

-- 结构版本记录表：应用启动时读它判断库是否需要升级
CREATE TABLE schema_version (
    version     VARCHAR(32)  NOT NULL,
    description VARCHAR(256) DEFAULT NULL,
    applied_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (version)
);

-- 血缘平台初始表结构（H2 2.x，本地/嵌入式）
-- 所有业务表均带 tenant_id + project_id，查询强制按租户过滤。

CREATE TABLE tenant (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code         VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    status       TINYINT      NOT NULL DEFAULT 1,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_code UNIQUE (code)
);

CREATE TABLE project (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL,
    code         VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    description  VARCHAR(512) DEFAULT NULL,
    status       TINYINT      NOT NULL DEFAULT 1,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_project UNIQUE (tenant_id, code)
);

CREATE TABLE metadata_source (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT        NOT NULL,
    name           VARCHAR(128)  NOT NULL,
    type           VARCHAR(32)   NOT NULL,
    base_url       VARCHAR(512)  NOT NULL,
    credential     VARCHAR(1024) DEFAULT NULL,
    extra_config   TEXT,
    priority       INT           NOT NULL DEFAULT 100,
    enabled        TINYINT       NOT NULL DEFAULT 1,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_source UNIQUE (tenant_id, name)
);

-- 索引
CREATE INDEX idx_project_tenant ON project (tenant_id);

CREATE INDEX idx_source_enabled ON metadata_source (tenant_id, enabled, priority);

-- 元数据目录 + 血缘版本下沉到目标表（H2 2.x）
--
-- 两件事：
--   1. 新增 meta_table / meta_column —— 我们自己维护的元数据，供 SQL 解析时查表结构。
--      与血缘表**隔离存储**：血缘是从 SQL 推导出来的（含临时表、推断列），
--      元数据是数据库里真实存在的结构。混在一起会让一次错误推断污染元数据，
--      而元数据又反过来喂给解析器，形成正反馈。
--   2. 血缘四张表重建：版本从「项目级」下沉到「目标表级」。
--      原设计里 version_no 在 (tenant, project) 内递增、每张表都绑 version_id，
--      导致解析 A 表再解析 B 表后，current 版本里只剩 B，A 从目录中消失。
--
-- ⚠️ 血缘四张表是 drop + create。旧版本记录里没有目标表的概念，无法反推每个版本
--    属于哪张表，做不到忠实迁移。这四张表此前没有任何 REST 写入口，线上必为空。
--    tenant / project / metadata_source 不受影响。

-- ---------------------------------------------------------------
-- 一、元数据目录
-- ---------------------------------------------------------------

CREATE TABLE meta_table (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT        NOT NULL,
    project_id   BIGINT        NOT NULL,
    catalog_name VARCHAR(128)  NOT NULL,
    schema_name  VARCHAR(128)  NOT NULL,
    table_name   VARCHAR(256)  NOT NULL,
    full_name    VARCHAR(512)  NOT NULL,
    -- FULL / INCRE / SNAPSHOT_FULL / SNAPSHOT_INCRE / ZIPPER / ARCH
    table_type   VARCHAR(32)   DEFAULT NULL,
    -- 中文名
    comment      VARCHAR(512)  DEFAULT NULL,
    remark       VARCHAR(1024) DEFAULT NULL,
    db_type      VARCHAR(32)   DEFAULT NULL,
    -- DDL / GRAVITINO / DBX / MANUAL：这行结构是怎么来的
    source       VARCHAR(16)   NOT NULL,
    -- 来自哪条 metadata_source
    source_id    BIGINT        DEFAULT NULL,
    synced_at    TIMESTAMP     DEFAULT NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_meta_table UNIQUE (tenant_id, project_id, full_name)
);

CREATE TABLE meta_column (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT        NOT NULL,
    project_id   BIGINT        NOT NULL,
    table_id     BIGINT        NOT NULL,
    column_name  VARCHAR(256)  NOT NULL,
    full_name    VARCHAR(750)  NOT NULL,
    data_type    VARCHAR(128)  DEFAULT NULL,
    comment      VARCHAR(512)  DEFAULT NULL,
    remark       VARCHAR(1024) DEFAULT NULL,
    ordinal      INT           NOT NULL DEFAULT 0,
    is_partition TINYINT       NOT NULL DEFAULT 0,
    nullable     TINYINT       NOT NULL DEFAULT 1,
    is_primary   TINYINT       NOT NULL DEFAULT 0,
    source       VARCHAR(16)   NOT NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_meta_column UNIQUE (tenant_id, project_id, full_name)
);

-- 血缘侧的表目录：累积保存，不再按版本切分。
-- 只放血缘必需的字段；中文名/备注/表类型这类描述属性归 meta_table 单独维护，
-- 展示时按 full_name 左关联带出来，避免两边各存一份导致不一致。
CREATE TABLE lineage_table (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL,
    project_id   BIGINT       NOT NULL,
    catalog_name VARCHAR(128) NOT NULL,
    schema_name  VARCHAR(128) NOT NULL,
    table_name   VARCHAR(256) NOT NULL,
    full_name    VARCHAR(512) NOT NULL,
    db_type      VARCHAR(32)  DEFAULT NULL,
    is_temp      TINYINT      NOT NULL DEFAULT 0,
    table_type   VARCHAR(32)   DEFAULT NULL,
    comment      VARCHAR(512)  DEFAULT NULL,
    remark       VARCHAR(1024) DEFAULT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_lineage_table UNIQUE (tenant_id, project_id, full_name)
);

CREATE TABLE lineage_column (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL,
    project_id   BIGINT       NOT NULL,
    table_id     BIGINT       NOT NULL,
    column_name  VARCHAR(256) NOT NULL,
    full_name    VARCHAR(750)  NOT NULL,
    ordinal      INT          NOT NULL DEFAULT 0,
    is_partition TINYINT      NOT NULL DEFAULT 0,
    data_type    VARCHAR(128)  DEFAULT NULL,
    comment      VARCHAR(512)  DEFAULT NULL,
    remark       VARCHAR(1024) DEFAULT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_lineage_column UNIQUE (tenant_id, project_id, full_name)
);

-- 一个版本 = 「某张目标表的一次血缘更新」。
-- version_no 在同一目标表内递增，is_current 也在同一目标表内互斥 ——
-- 这样解析不同的表互不影响，各自的历史各自留存。
CREATE TABLE lineage_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    project_id      BIGINT       NOT NULL,
    target_table_id BIGINT       NOT NULL,
    version_no      INT          NOT NULL,
    name            VARCHAR(128) DEFAULT NULL,
    db_type         VARCHAR(32)  NOT NULL,
    sql_hash        CHAR(64)     NOT NULL,
    sql_text        CLOB,
    is_current      TINYINT      NOT NULL DEFAULT 0,
    stat_tables     INT          NOT NULL DEFAULT 0,
    stat_columns    INT          NOT NULL DEFAULT 0,
    stat_edges      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_lineage_version UNIQUE (tenant_id, project_id, target_table_id, version_no)
);

CREATE TABLE lineage_edge (
    id             BIGINT     NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT     NOT NULL,
    project_id     BIGINT     NOT NULL,
    version_id     BIGINT     NOT NULL,
    target_col_id  BIGINT     NOT NULL,
    source_col_id  BIGINT     NOT NULL,
    transform      TEXT,
    is_cyclic      TINYINT    NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_lineage_edge UNIQUE (version_id, target_col_id, source_col_id)
);

-- ---------------------------------------------------------------
-- 三、索引
--
-- 图遍历会反复按这些列做点查，缺索引时递归 CTE 每一跳都是全表扫。
-- ---------------------------------------------------------------

CREATE INDEX idx_meta_table_schema ON meta_table (tenant_id, project_id, schema_name);

CREATE INDEX idx_meta_column_table ON meta_column (table_id);

CREATE INDEX idx_lineage_table_schema ON lineage_table (tenant_id, project_id, schema_name);

CREATE INDEX idx_lineage_column_table ON lineage_column (table_id);

CREATE INDEX idx_lineage_version_target ON lineage_version (tenant_id, project_id, target_table_id, is_current);

-- 按 SQL 哈希查已有版本做幂等，沿用 V1 的 idx_version_hash（随表 drop 后需重建）
CREATE INDEX idx_lineage_version_hash ON lineage_version (tenant_id, project_id, sql_hash);

CREATE INDEX idx_lineage_edge_target ON lineage_edge (version_id, target_col_id);

CREATE INDEX idx_lineage_edge_source ON lineage_edge (version_id, source_col_id);

CREATE TABLE sync_job (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT        NOT NULL,
    project_id      BIGINT        NOT NULL,
    source_id       BIGINT        NOT NULL,
    scope           VARCHAR(16)   NOT NULL,
    source_catalog  VARCHAR(128)  DEFAULT NULL,
    source_database VARCHAR(128)  DEFAULT NULL,
    source_schema   VARCHAR(128)  DEFAULT NULL,
    connection_id   VARCHAR(128)  DEFAULT NULL,
    target_catalog  VARCHAR(128)  DEFAULT NULL,
    tables          TEXT,
    overwrite_manual TINYINT      NOT NULL DEFAULT 0,
    status          VARCHAR(16)   NOT NULL,
    total           INT           NOT NULL DEFAULT 0,
    done            INT           NOT NULL DEFAULT 0,
    created_cnt     INT           NOT NULL DEFAULT 0,
    updated_cnt     INT           NOT NULL DEFAULT 0,
    skipped_cnt     INT           NOT NULL DEFAULT 0,
    failed_cnt      INT           NOT NULL DEFAULT 0,
    message         VARCHAR(1024) DEFAULT NULL,
    failures        TEXT,
    started_at      TIMESTAMP     DEFAULT NULL,
    finished_at     TIMESTAMP     DEFAULT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX idx_sync_job_recent ON sync_job (tenant_id, project_id, id);

CREATE TABLE data_catalog (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT        NOT NULL,
    project_id  BIGINT        NOT NULL,
    name        VARCHAR(128)  NOT NULL,
    is_default  TINYINT       NOT NULL DEFAULT 0,
    description VARCHAR(512)  DEFAULT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_data_catalog UNIQUE (tenant_id, project_id, name)
);

CREATE TABLE temp_rule (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT        NOT NULL,
    project_id   BIGINT        NOT NULL,
    catalog_name VARCHAR(128)  DEFAULT NULL,
    target       VARCHAR(16)   NOT NULL,
    match_type   VARCHAR(16)   NOT NULL,
    pattern      VARCHAR(256)  NOT NULL,
    enabled      TINYINT       NOT NULL DEFAULT 1,
    description  VARCHAR(512)  DEFAULT NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX idx_temp_rule_scope ON temp_rule (tenant_id, project_id, enabled);

-- >>> 表与字段注释（由 tools/gen-sql-comments.py 生成，勿手工编辑）

COMMENT ON TABLE schema_version IS '数据库结构版本记录。仅作历史版本信息留存；实际的建表与升级由 Flyway 的 flyway_schema_history 托管';
COMMENT ON COLUMN schema_version.version IS '已应用的结构版本号，如 1.0.3';
COMMENT ON COLUMN schema_version.description IS '该版本做了什么变更';
COMMENT ON COLUMN schema_version.applied_at IS '执行时间';

COMMENT ON TABLE tenant IS '租户。数据隔离的最外层维度';
COMMENT ON COLUMN tenant.id IS '主键';
COMMENT ON COLUMN tenant.code IS '租户编码，全局唯一，创建后不可改';
COMMENT ON COLUMN tenant.name IS '租户名称，页面展示用';
COMMENT ON COLUMN tenant.status IS '1-启用 0-停用。停用后切换器里置灰，不做物理删除';
COMMENT ON COLUMN tenant.created_at IS '创建时间';
COMMENT ON COLUMN tenant.updated_at IS '更新时间';

COMMENT ON TABLE project IS '项目。租户之下的第二层隔离维度，业务数据都挂在项目上';
COMMENT ON COLUMN project.id IS '主键';
COMMENT ON COLUMN project.tenant_id IS '所属租户';
COMMENT ON COLUMN project.code IS '项目编码，租户内唯一，创建后不可改';
COMMENT ON COLUMN project.name IS '项目名称';
COMMENT ON COLUMN project.description IS '项目描述';
COMMENT ON COLUMN project.status IS '1-启用 0-停用';
COMMENT ON COLUMN project.created_at IS '创建时间';
COMMENT ON COLUMN project.updated_at IS '更新时间';

COMMENT ON TABLE metadata_source IS '元数据服务配置。SQL 解析时按 priority 依次向这些来源要表结构。租户级共享 —— 同一租户下所有项目共用一份';
COMMENT ON COLUMN metadata_source.id IS '主键';
COMMENT ON COLUMN metadata_source.tenant_id IS '所属租户';
COMMENT ON COLUMN metadata_source.name IS '配置名称，租户内唯一';
COMMENT ON COLUMN metadata_source.type IS 'GRAVITINO / DBX / CATALOG。CATALOG 是内置的本地元数据目录，不可删除';
COMMENT ON COLUMN metadata_source.base_url IS '服务地址。CATALOG 类型为占位值 local://catalog，不会被真的访问';
COMMENT ON COLUMN metadata_source.credential IS '凭据密文（AES-256-GCM）。接口响应中永不回传明文，禁止明文入库';
COMMENT ON COLUMN metadata_source.extra_config IS '各类型的差异化配置，JSON。dbx 用它指定 connectionId / database / schema';
COMMENT ON COLUMN metadata_source.priority IS '数字小的优先。多个来源时决定先问谁';
COMMENT ON COLUMN metadata_source.enabled IS '1-启用 0-停用。停用的来源不参与解析';
COMMENT ON COLUMN metadata_source.created_at IS '创建时间';
COMMENT ON COLUMN metadata_source.updated_at IS '更新时间';

COMMENT ON TABLE meta_table IS '元数据目录中的表。这是数据库里真实存在的表结构，供 SQL 解析时查；与血缘表分开存储，避免推断结果污染事实';
COMMENT ON COLUMN meta_table.id IS '主键';
COMMENT ON COLUMN meta_table.tenant_id IS '所属租户';
COMMENT ON COLUMN meta_table.project_id IS '所属项目';
COMMENT ON COLUMN meta_table.catalog_name IS '数据目录，指向 data_catalog.name。没指定时落到默认目录，1.0.4 起由 NOT NULL 强制';
COMMENT ON COLUMN meta_table.schema_name IS '库名，保留原始大小写供展示';
COMMENT ON COLUMN meta_table.table_name IS '表名，保留原始大小写供展示';
COMMENT ON COLUMN meta_table.full_name IS 'catalog.schema.table，恒为小写。唯一键与解析时的点查键';
COMMENT ON COLUMN meta_table.table_type IS '表类型：FULL 全量 / INCRE 增量 / SNAPSHOT_FULL 全量快照 / SNAPSHOT_INCRE 增量快照 / ZIPPER 拉链 / ARCH 归档';
COMMENT ON COLUMN meta_table.comment IS '表中文名';
COMMENT ON COLUMN meta_table.remark IS '备注';
COMMENT ON COLUMN meta_table.db_type IS '该表所属的数据库方言';
COMMENT ON COLUMN meta_table.source IS '这行结构从哪来：DDL 建表语句 / GRAVITINO / DBX / MANUAL 手工维护';
COMMENT ON COLUMN meta_table.source_id IS '来自哪条 metadata_source；DDL 导入与手工录入时为空';
COMMENT ON COLUMN meta_table.synced_at IS '最近一次从外部服务同步的时间';
COMMENT ON COLUMN meta_table.created_at IS '创建时间';
COMMENT ON COLUMN meta_table.updated_at IS '更新时间';

COMMENT ON TABLE meta_column IS '元数据目录中的字段';
COMMENT ON COLUMN meta_column.id IS '主键';
COMMENT ON COLUMN meta_column.tenant_id IS '所属租户';
COMMENT ON COLUMN meta_column.project_id IS '所属项目';
COMMENT ON COLUMN meta_column.table_id IS '所属 meta_table';
COMMENT ON COLUMN meta_column.column_name IS '字段名，保留原始大小写供展示';
COMMENT ON COLUMN meta_column.full_name IS 'catalog.schema.table.column，恒为小写';
COMMENT ON COLUMN meta_column.data_type IS '字段类型原文，如 decimal(12,2)';
COMMENT ON COLUMN meta_column.comment IS '字段中文名';
COMMENT ON COLUMN meta_column.remark IS '备注';
COMMENT ON COLUMN meta_column.ordinal IS '字段顺序，从 1 开始';
COMMENT ON COLUMN meta_column.is_partition IS '1-分区字段 0-普通字段。Hive/Spark 的分区列对血缘是必需的';
COMMENT ON COLUMN meta_column.nullable IS '1-可空 0-非空';
COMMENT ON COLUMN meta_column.is_primary IS '1-主键 0-非主键';
COMMENT ON COLUMN meta_column.source IS '同 meta_table.source，字段可以被单独手工维护';
COMMENT ON COLUMN meta_column.created_at IS '创建时间';
COMMENT ON COLUMN meta_column.updated_at IS '更新时间';

COMMENT ON TABLE lineage_table IS '血缘中出现过的表。累积保存、不按版本切分；只放血缘必需的字段，中文名等描述属性归 meta_table';
COMMENT ON COLUMN lineage_table.id IS '主键';
COMMENT ON COLUMN lineage_table.tenant_id IS '所属租户';
COMMENT ON COLUMN lineage_table.project_id IS '所属项目';
COMMENT ON COLUMN lineage_table.catalog_name IS '数据目录，取 full_name 的第一段。SQL 里没写目录时由解析层补默认目录，1.0.4 起由 NOT NULL 强制';
COMMENT ON COLUMN lineage_table.schema_name IS '库名';
COMMENT ON COLUMN lineage_table.table_name IS '表名';
COMMENT ON COLUMN lineage_table.full_name IS 'catalog.schema.table，保留原始大小写（要显示在血缘图上）；与 meta_table 关联时对本侧加 lower()';
COMMENT ON COLUMN lineage_table.db_type IS '解析所用方言';
COMMENT ON COLUMN lineage_table.is_temp IS '1-临时表或子查询产物 0-真实表';
COMMENT ON COLUMN lineage_table.created_at IS '创建时间';
COMMENT ON COLUMN lineage_table.updated_at IS '更新时间';
COMMENT ON COLUMN lineage_table.table_type IS '表类型，保存血缘时从元数据来源快照下来；只有本地元数据目录有这个概念，远程来源为空';
COMMENT ON COLUMN lineage_table.comment IS '表中文名，保存血缘时从解析所用的元数据来源快照下来。不与 meta_table 关联取——两侧的数据目录段天然对不上';
COMMENT ON COLUMN lineage_table.remark IS '备注，同上，快照自元数据来源';

COMMENT ON TABLE lineage_column IS '血缘中出现过的字段';
COMMENT ON COLUMN lineage_column.id IS '主键';
COMMENT ON COLUMN lineage_column.tenant_id IS '所属租户';
COMMENT ON COLUMN lineage_column.project_id IS '所属项目';
COMMENT ON COLUMN lineage_column.table_id IS '所属 lineage_table';
COMMENT ON COLUMN lineage_column.column_name IS '字段名';
COMMENT ON COLUMN lineage_column.full_name IS 'catalog.schema.table.column';
COMMENT ON COLUMN lineage_column.ordinal IS '字段顺序。血缘侧的字段来自 SQL 解析，没有表定义里的列序，通常为 0';
COMMENT ON COLUMN lineage_column.is_partition IS '1-分区字段 0-普通字段';
COMMENT ON COLUMN lineage_column.created_at IS '创建时间';
COMMENT ON COLUMN lineage_column.updated_at IS '更新时间';
COMMENT ON COLUMN lineage_column.data_type IS '字段类型，保存血缘时快照自元数据来源。派生列（sum(x) as y）源表没有对应列，为空';
COMMENT ON COLUMN lineage_column.comment IS '字段中文名，保存血缘时快照自元数据来源';
COMMENT ON COLUMN lineage_column.remark IS '备注，同上，快照自元数据来源';

COMMENT ON TABLE lineage_version IS '血缘版本。一个版本 = 某张目标表的一次血缘更新；版本号与 is_current 都在同一目标表内生效，解析不同的表互不影响';
COMMENT ON COLUMN lineage_version.id IS '主键';
COMMENT ON COLUMN lineage_version.tenant_id IS '所属租户';
COMMENT ON COLUMN lineage_version.project_id IS '所属项目';
COMMENT ON COLUMN lineage_version.target_table_id IS '该版本属于哪张目标表。版本挂在目标表上，不是挂在项目上';
COMMENT ON COLUMN lineage_version.version_no IS '同一目标表内递增，从 1 开始';
COMMENT ON COLUMN lineage_version.name IS '版本名称，可空';
COMMENT ON COLUMN lineage_version.db_type IS '解析所用方言';
COMMENT ON COLUMN lineage_version.sql_hash IS 'SQL 文本的 sha256，用于幂等去重';
COMMENT ON COLUMN lineage_version.sql_text IS '原始 SQL，供版本对比与算子下钻';
COMMENT ON COLUMN lineage_version.is_current IS '1-当前版本。同一目标表内至多一条';
COMMENT ON COLUMN lineage_version.stat_tables IS '该版本涉及的表数量';
COMMENT ON COLUMN lineage_version.stat_columns IS '该版本涉及的字段数量';
COMMENT ON COLUMN lineage_version.stat_edges IS '该版本的列级边数量';
COMMENT ON COLUMN lineage_version.created_at IS '创建时间';

COMMENT ON TABLE lineage_edge IS '列级血缘边。一条边 = 一个下游字段来自一个上游字段';
COMMENT ON COLUMN lineage_edge.id IS '主键';
COMMENT ON COLUMN lineage_edge.tenant_id IS '所属租户';
COMMENT ON COLUMN lineage_edge.project_id IS '所属项目';
COMMENT ON COLUMN lineage_edge.version_id IS '所属版本。删除版本时按它批量清理';
COMMENT ON COLUMN lineage_edge.target_col_id IS '下游（被写入）字段';
COMMENT ON COLUMN lineage_edge.source_col_id IS '上游（来源）字段';
COMMENT ON COLUMN lineage_edge.transform IS '转换表达式原文。目前尚未写入，见 docs/KNOWN_ISSUES.md';
COMMENT ON COLUMN lineage_edge.is_cyclic IS '1-该边落在环上。环由解析时的 Tarjan 检测标记';

COMMENT ON TABLE sync_job IS '元数据导入任务。按 catalog / 库批量导入可能上万张表，同步请求必然超时，因此改成异步执行并记录进度';
COMMENT ON COLUMN sync_job.id IS '主键';
COMMENT ON COLUMN sync_job.tenant_id IS '所属租户';
COMMENT ON COLUMN sync_job.project_id IS '所属项目。导入结果写进哪个项目的元数据目录';
COMMENT ON COLUMN sync_job.source_id IS '从哪条 metadata_source 导入';
COMMENT ON COLUMN sync_job.scope IS '导入范围：CATALOG 整个数据目录 / SCHEMA 整个库 / TABLE 指定的若干张表';
COMMENT ON COLUMN sync_job.source_catalog IS '源端数据目录（Gravitino 的 catalog）';
COMMENT ON COLUMN sync_job.source_database IS '源端数据库（dbx 的 database）';
COMMENT ON COLUMN sync_job.source_schema IS '源端库名。scope=CATALOG 时为空，由任务自己展开';
COMMENT ON COLUMN sync_job.connection_id IS 'dbx 中已保存的连接 id';
COMMENT ON COLUMN sync_job.target_catalog IS '导入到哪个数据目录。为空时沿用源端。库名与表名不可改 —— 改了 SQL 解析按 schema.table 就对不上';
COMMENT ON COLUMN sync_job.tables IS 'scope=TABLE 时要导入的表名清单，JSON 数组';
COMMENT ON COLUMN sync_job.overwrite_manual IS '1-覆盖人工维护过的内容 0-跳过它们';
COMMENT ON COLUMN sync_job.status IS 'PENDING 待执行 / RUNNING 执行中 / SUCCESS 全部成功 / PARTIAL 部分失败 / FAILED 整体失败';
COMMENT ON COLUMN sync_job.total IS '待导入的表总数，展开完成后才有值';
COMMENT ON COLUMN sync_job.done IS '已处理数量，用于进度条';
COMMENT ON COLUMN sync_job.created_cnt IS '新增的表数';
COMMENT ON COLUMN sync_job.updated_cnt IS '更新的表数';
COMMENT ON COLUMN sync_job.skipped_cnt IS '跳过的表数（人工维护过且未勾选覆盖）';
COMMENT ON COLUMN sync_job.failed_cnt IS '失败的表数';
COMMENT ON COLUMN sync_job.message IS '整体失败的原因摘要';
COMMENT ON COLUMN sync_job.failures IS '失败明细，JSON 数组';
COMMENT ON COLUMN sync_job.started_at IS '开始执行时间';
COMMENT ON COLUMN sync_job.finished_at IS '结束时间';
COMMENT ON COLUMN sync_job.created_at IS '提交时间';
COMMENT ON COLUMN sync_job.updated_at IS '更新时间';

COMMENT ON TABLE data_catalog IS '本地元数据的数据目录。每个项目有且仅有一个默认目录，贴建表语句导入时没指定目录就落到它，因此 meta_table 里不存在没有数据目录的表';
COMMENT ON COLUMN data_catalog.id IS '主键';
COMMENT ON COLUMN data_catalog.tenant_id IS '所属租户';
COMMENT ON COLUMN data_catalog.project_id IS '所属项目';
COMMENT ON COLUMN data_catalog.name IS '目录名，项目内唯一，统一小写。它是表全名的第一段，因此不能含点号';
COMMENT ON COLUMN data_catalog.is_default IS '1-默认目录 0-普通目录。每个项目最多一条为 1，由服务层保证；默认目录不允许删除';
COMMENT ON COLUMN data_catalog.description IS '说明';
COMMENT ON COLUMN data_catalog.created_at IS '创建时间';
COMMENT ON COLUMN data_catalog.updated_at IS '更新时间';

COMMENT ON TABLE temp_rule IS '临时库/表规则。命中的表在血缘图上会被穿透掉（上下游直接相连，而不是把图断成两段），保存血缘时一律过滤，库里永远不存临时表';
COMMENT ON COLUMN temp_rule.id IS '主键';
COMMENT ON COLUMN temp_rule.tenant_id IS '所属租户';
COMMENT ON COLUMN temp_rule.project_id IS '所属项目';
COMMENT ON COLUMN temp_rule.catalog_name IS '只对该数据目录生效；为空表示对该项目全部目录生效';
COMMENT ON COLUMN temp_rule.target IS 'SCHEMA-匹配库名（整个库都算临时，如 tmp / test）TABLE-匹配表名（如 tmp_*）';
COMMENT ON COLUMN temp_rule.match_type IS 'GLOB-通配符 REGEX-正则。两者对 tmp_* 的解释完全不同：通配符下匹配 tmp_abc，正则下 * 修饰的是前一个字符 _，反而匹配不到 tmp_abc';
COMMENT ON COLUMN temp_rule.pattern IS '匹配表达式，不区分大小写';
COMMENT ON COLUMN temp_rule.enabled IS '1-启用 0-停用。停用的规则不参与匹配';
COMMENT ON COLUMN temp_rule.description IS '说明';
COMMENT ON COLUMN temp_rule.created_at IS '创建时间';
COMMENT ON COLUMN temp_rule.updated_at IS '更新时间';

-- <<< 注释结束
