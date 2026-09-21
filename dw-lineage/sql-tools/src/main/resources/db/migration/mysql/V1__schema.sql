-- SQL 血缘分析平台 · 表结构（MySQL 8.0+）
-- 结构版本：1.0.5
--
-- 全新安装执行本文件，再执行 02_init_data.sql。
-- 升级已有库请改用 upgrade/ 目录下的脚本，不要重复执行本文件。
--
-- 所有业务表都带 tenant_id + project_id，查询强制按租户过滤。

-- 结构版本记录表：应用启动时读它判断库是否需要升级
CREATE TABLE schema_version (
    version     VARCHAR(32)  NOT NULL COMMENT '已应用的结构版本号，如 1.0.3',
    description VARCHAR(256) DEFAULT NULL COMMENT '该版本做了什么变更',
    applied_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
    PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据库结构版本记录。仅作历史版本信息留存；实际的建表与升级由 Flyway 的 flyway_schema_history 托管';

-- 血缘平台初始表结构（MySQL 8.0+，递归 CTE 需要 8.0）
-- 所有业务表均带 tenant_id + project_id，查询强制按租户过滤。

CREATE TABLE tenant (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    code         VARCHAR(64)  NOT NULL COMMENT '租户编码，全局唯一，创建后不可改',
    name         VARCHAR(128) NOT NULL COMMENT '租户名称，页面展示用',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用。停用后切换器里置灰，不做物理删除',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户。数据隔离的最外层维度';

CREATE TABLE project (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id    BIGINT       NOT NULL COMMENT '所属租户',
    code         VARCHAR(64)  NOT NULL COMMENT '项目编码，租户内唯一，创建后不可改',
    name         VARCHAR(128) NOT NULL COMMENT '项目名称',
    description  VARCHAR(512) DEFAULT NULL COMMENT '项目描述',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_project (tenant_id, code),
    KEY idx_project_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目。租户之下的第二层隔离维度，业务数据都挂在项目上';

CREATE TABLE metadata_source (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id      BIGINT        NOT NULL COMMENT '所属租户',
    name           VARCHAR(128)  NOT NULL COMMENT '配置名称，租户内唯一',
    type           VARCHAR(32)   NOT NULL COMMENT 'GRAVITINO / DBX / CATALOG。CATALOG 是内置的本地元数据目录，不可删除',
    base_url       VARCHAR(512)  NOT NULL COMMENT '服务地址。CATALOG 类型为占位值 local://catalog，不会被真的访问',
    credential     VARCHAR(1024) DEFAULT NULL COMMENT '凭据密文（AES-256-GCM）。接口响应中永不回传明文，禁止明文入库',
    extra_config   TEXT COMMENT '各类型的差异化配置，JSON。dbx 用它指定 connectionId / database / schema',
    priority       INT           NOT NULL DEFAULT 100 COMMENT '数字小的优先。多个来源时决定先问谁',
    enabled        TINYINT       NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用。停用的来源不参与解析',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_source (tenant_id, name),
    KEY idx_source_enabled (tenant_id, enabled, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='元数据服务配置。SQL 解析时按 priority 依次向这些来源要表结构。租户级共享 —— 同一租户下所有项目共用一份';

-- 元数据目录 + 血缘版本下沉到目标表（MySQL 8+）
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
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id    BIGINT        NOT NULL COMMENT '所属租户',
    project_id   BIGINT        NOT NULL COMMENT '所属项目',
    catalog_name VARCHAR(128)  NOT NULL COMMENT '数据目录，指向 data_catalog.name。没指定时落到默认目录，1.0.4 起由 NOT NULL 强制',
    schema_name  VARCHAR(128)  NOT NULL COMMENT '库名，保留原始大小写供展示',
    table_name   VARCHAR(256)  NOT NULL COMMENT '表名，保留原始大小写供展示',
    full_name    VARCHAR(512)  NOT NULL COMMENT 'catalog.schema.table，恒为小写。唯一键与解析时的点查键',
    table_type   VARCHAR(32)   DEFAULT NULL COMMENT '表类型：FULL 全量 / INCRE 增量 / SNAPSHOT_FULL 全量快照 / SNAPSHOT_INCRE 增量快照 / ZIPPER 拉链 / ARCH 归档',
    `comment`    VARCHAR(512)  DEFAULT NULL COMMENT '表中文名',
    remark       VARCHAR(1024) DEFAULT NULL COMMENT '备注',
    db_type      VARCHAR(32)   DEFAULT NULL COMMENT '该表所属的数据库方言',
    source       VARCHAR(16)   NOT NULL COMMENT '这行结构从哪来：DDL 建表语句 / GRAVITINO / DBX / MANUAL 手工维护',
    source_id    BIGINT        DEFAULT NULL COMMENT '来自哪条 metadata_source；DDL 导入与手工录入时为空',
    synced_at    DATETIME      DEFAULT NULL COMMENT '最近一次从外部服务同步的时间',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_meta_table (tenant_id, project_id, full_name),
    KEY idx_meta_table_schema (tenant_id, project_id, schema_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='元数据目录中的表。这是数据库里真实存在的表结构，供 SQL 解析时查；与血缘表分开存储，避免推断结果污染事实';

CREATE TABLE meta_column (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id    BIGINT        NOT NULL COMMENT '所属租户',
    project_id   BIGINT        NOT NULL COMMENT '所属项目',
    table_id     BIGINT        NOT NULL COMMENT '所属 meta_table',
    column_name  VARCHAR(256)  NOT NULL COMMENT '字段名，保留原始大小写供展示',
    full_name    VARCHAR(750)  NOT NULL COMMENT 'catalog.schema.table.column，恒为小写',
    data_type    VARCHAR(128)  DEFAULT NULL COMMENT '字段类型原文，如 decimal(12,2)',
    `comment`    VARCHAR(512)  DEFAULT NULL COMMENT '字段中文名',
    remark       VARCHAR(1024) DEFAULT NULL COMMENT '备注',
    ordinal      INT           NOT NULL DEFAULT 0 COMMENT '字段顺序，从 1 开始',
    is_partition TINYINT       NOT NULL DEFAULT 0 COMMENT '1-分区字段 0-普通字段。Hive/Spark 的分区列对血缘是必需的',
    nullable     TINYINT       NOT NULL DEFAULT 1 COMMENT '1-可空 0-非空',
    is_primary   TINYINT       NOT NULL DEFAULT 0 COMMENT '1-主键 0-非主键',
    source       VARCHAR(16)   NOT NULL COMMENT '同 meta_table.source，字段可以被单独手工维护',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_meta_column (tenant_id, project_id, full_name),
    KEY idx_meta_column_table (table_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='元数据目录中的字段';

-- 血缘侧的表目录：累积保存，不再按版本切分。
-- 只放血缘必需的字段；中文名/备注/表类型这类描述属性归 meta_table 单独维护，
-- 展示时按 full_name 左关联带出来，避免两边各存一份导致不一致。
CREATE TABLE lineage_table (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id    BIGINT       NOT NULL COMMENT '所属租户',
    project_id   BIGINT       NOT NULL COMMENT '所属项目',
    catalog_name VARCHAR(128) NOT NULL COMMENT '数据目录，取 full_name 的第一段。SQL 里没写目录时由解析层补默认目录，1.0.4 起由 NOT NULL 强制',
    schema_name  VARCHAR(128) NOT NULL COMMENT '库名',
    table_name   VARCHAR(256) NOT NULL COMMENT '表名',
    full_name    VARCHAR(512) NOT NULL COMMENT 'catalog.schema.table，保留原始大小写（要显示在血缘图上）；与 meta_table 关联时对本侧加 lower()',
    db_type      VARCHAR(32)  DEFAULT NULL COMMENT '解析所用方言',
    is_temp      TINYINT      NOT NULL DEFAULT 0 COMMENT '1-临时表或子查询产物 0-真实表',
    table_type   VARCHAR(32)   DEFAULT NULL COMMENT '表类型，保存血缘时从元数据来源快照下来；只有本地元数据目录有这个概念，远程来源为空',
    `comment`    VARCHAR(512)  DEFAULT NULL COMMENT '表中文名，保存血缘时从解析所用的元数据来源快照下来。不与 meta_table 关联取——两侧的数据目录段天然对不上',
    remark       VARCHAR(1024) DEFAULT NULL COMMENT '备注，同上，快照自元数据来源',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_lineage_table (tenant_id, project_id, full_name),
    KEY idx_lineage_table_schema (tenant_id, project_id, schema_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='血缘中出现过的表。累积保存、不按版本切分；只放血缘必需的字段，中文名等描述属性归 meta_table';

CREATE TABLE lineage_column (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id    BIGINT       NOT NULL COMMENT '所属租户',
    project_id   BIGINT       NOT NULL COMMENT '所属项目',
    table_id     BIGINT       NOT NULL COMMENT '所属 lineage_table',
    column_name  VARCHAR(256) NOT NULL COMMENT '字段名',
    full_name    VARCHAR(750)  NOT NULL COMMENT 'catalog.schema.table.column',
    ordinal      INT          NOT NULL DEFAULT 0 COMMENT '字段顺序。血缘侧的字段来自 SQL 解析，没有表定义里的列序，通常为 0',
    is_partition TINYINT      NOT NULL DEFAULT 0 COMMENT '1-分区字段 0-普通字段',
    data_type    VARCHAR(128)  DEFAULT NULL COMMENT '字段类型，保存血缘时快照自元数据来源。派生列（sum(x) as y）源表没有对应列，为空',
    `comment`    VARCHAR(512)  DEFAULT NULL COMMENT '字段中文名，保存血缘时快照自元数据来源',
    remark       VARCHAR(1024) DEFAULT NULL COMMENT '备注，同上，快照自元数据来源',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_lineage_column (tenant_id, project_id, full_name),
    KEY idx_lineage_column_table (table_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='血缘中出现过的字段';

-- 一个版本 = 「某张目标表的一次血缘更新」。
-- version_no 在同一目标表内递增，is_current 也在同一目标表内互斥 ——
-- 这样解析不同的表互不影响，各自的历史各自留存。
CREATE TABLE lineage_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id       BIGINT       NOT NULL COMMENT '所属租户',
    project_id      BIGINT       NOT NULL COMMENT '所属项目',
    target_table_id BIGINT       NOT NULL COMMENT '该版本属于哪张目标表。版本挂在目标表上，不是挂在项目上',
    version_no      INT          NOT NULL COMMENT '同一目标表内递增，从 1 开始',
    name            VARCHAR(128) DEFAULT NULL COMMENT '版本名称，可空',
    db_type         VARCHAR(32)  NOT NULL COMMENT '解析所用方言',
    sql_hash        CHAR(64)     NOT NULL COMMENT 'SQL 文本的 sha256，用于幂等去重',
    sql_text        LONGTEXT COMMENT '原始 SQL，供版本对比与算子下钻',
    is_current      TINYINT      NOT NULL DEFAULT 0 COMMENT '1-当前版本。同一目标表内至多一条',
    stat_tables     INT          NOT NULL DEFAULT 0 COMMENT '该版本涉及的表数量',
    stat_columns    INT          NOT NULL DEFAULT 0 COMMENT '该版本涉及的字段数量',
    stat_edges      INT          NOT NULL DEFAULT 0 COMMENT '该版本的列级边数量',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_lineage_version (tenant_id, project_id, target_table_id, version_no),
    KEY idx_lineage_version_target (tenant_id, project_id, target_table_id, is_current),
    -- 按 SQL 哈希查已有版本做幂等，沿用 V1 的 idx_version_hash（随表 drop 后需重建）
    KEY idx_lineage_version_hash (tenant_id, project_id, sql_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='血缘版本。一个版本 = 某张目标表的一次血缘更新；版本号与 is_current 都在同一目标表内生效，解析不同的表互不影响';

CREATE TABLE lineage_edge (
    id             BIGINT     NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id      BIGINT     NOT NULL COMMENT '所属租户',
    project_id     BIGINT     NOT NULL COMMENT '所属项目',
    version_id     BIGINT     NOT NULL COMMENT '所属版本。删除版本时按它批量清理',
    target_col_id  BIGINT     NOT NULL COMMENT '下游（被写入）字段',
    source_col_id  BIGINT     NOT NULL COMMENT '上游（来源）字段',
    transform      TEXT COMMENT '转换表达式原文。目前尚未写入，见 docs/KNOWN_ISSUES.md',
    is_cyclic      TINYINT    NOT NULL DEFAULT 0 COMMENT '1-该边落在环上。环由解析时的 Tarjan 检测标记',
    PRIMARY KEY (id),
    UNIQUE KEY uk_lineage_edge (version_id, target_col_id, source_col_id),
    -- 图遍历会反复按这两列点查，缺索引时递归 CTE 每一跳都是全表扫
    KEY idx_lineage_edge_target (version_id, target_col_id),
    KEY idx_lineage_edge_source (version_id, source_col_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='列级血缘边。一条边 = 一个下游字段来自一个上游字段';

CREATE TABLE sync_job (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id       BIGINT        NOT NULL COMMENT '所属租户',
    project_id      BIGINT        NOT NULL COMMENT '所属项目。导入结果写进哪个项目的元数据目录',
    source_id       BIGINT        NOT NULL COMMENT '从哪条 metadata_source 导入',
    scope           VARCHAR(16)   NOT NULL COMMENT '导入范围：CATALOG 整个数据目录 / SCHEMA 整个库 / TABLE 指定的若干张表',
    source_catalog  VARCHAR(128)  DEFAULT NULL COMMENT '源端数据目录（Gravitino 的 catalog）',
    source_database VARCHAR(128)  DEFAULT NULL COMMENT '源端数据库（dbx 的 database）',
    source_schema   VARCHAR(128)  DEFAULT NULL COMMENT '源端库名。scope=CATALOG 时为空，由任务自己展开',
    connection_id   VARCHAR(128)  DEFAULT NULL COMMENT 'dbx 中已保存的连接 id',
    target_catalog  VARCHAR(128)  DEFAULT NULL COMMENT '导入到哪个数据目录。为空时沿用源端。库名与表名不可改 —— 改了 SQL 解析按 schema.table 就对不上',
    tables          TEXT COMMENT 'scope=TABLE 时要导入的表名清单，JSON 数组',
    overwrite_manual TINYINT      NOT NULL DEFAULT 0 COMMENT '1-覆盖人工维护过的内容 0-跳过它们',
    status          VARCHAR(16)   NOT NULL COMMENT 'PENDING 待执行 / RUNNING 执行中 / SUCCESS 全部成功 / PARTIAL 部分失败 / FAILED 整体失败',
    total           INT           NOT NULL DEFAULT 0 COMMENT '待导入的表总数，展开完成后才有值',
    done            INT           NOT NULL DEFAULT 0 COMMENT '已处理数量，用于进度条',
    created_cnt     INT           NOT NULL DEFAULT 0 COMMENT '新增的表数',
    updated_cnt     INT           NOT NULL DEFAULT 0 COMMENT '更新的表数',
    skipped_cnt     INT           NOT NULL DEFAULT 0 COMMENT '跳过的表数（人工维护过且未勾选覆盖）',
    failed_cnt      INT           NOT NULL DEFAULT 0 COMMENT '失败的表数',
    message         VARCHAR(1024) DEFAULT NULL COMMENT '整体失败的原因摘要',
    failures        TEXT COMMENT '失败明细，JSON 数组',
    started_at      DATETIME      DEFAULT NULL COMMENT '开始执行时间',
    finished_at     DATETIME      DEFAULT NULL COMMENT '结束时间',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_sync_job_recent (tenant_id, project_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='元数据导入任务。按 catalog / 库批量导入可能上万张表，同步请求必然超时，因此改成异步执行并记录进度';

CREATE TABLE data_catalog (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id   BIGINT        NOT NULL COMMENT '所属租户',
    project_id  BIGINT        NOT NULL COMMENT '所属项目',
    name        VARCHAR(128)  NOT NULL COMMENT '目录名，项目内唯一，统一小写。它是表全名的第一段，因此不能含点号',
    is_default  TINYINT       NOT NULL DEFAULT 0 COMMENT '1-默认目录 0-普通目录。每个项目最多一条为 1，由服务层保证；默认目录不允许删除',
    description VARCHAR(512)  DEFAULT NULL COMMENT '说明',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_data_catalog (tenant_id, project_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地元数据的数据目录。每个项目有且仅有一个默认目录，贴建表语句导入时没指定目录就落到它，因此 meta_table 里不存在没有数据目录的表';

CREATE TABLE temp_rule (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id    BIGINT        NOT NULL COMMENT '所属租户',
    project_id   BIGINT        NOT NULL COMMENT '所属项目',
    catalog_name VARCHAR(128)  DEFAULT NULL COMMENT '只对该数据目录生效；为空表示对该项目全部目录生效',
    target       VARCHAR(16)   NOT NULL COMMENT 'SCHEMA-匹配库名（整个库都算临时，如 tmp / test）TABLE-匹配表名（如 tmp_*）',
    match_type   VARCHAR(16)   NOT NULL COMMENT 'GLOB-通配符 REGEX-正则。两者对 tmp_* 的解释完全不同：通配符下匹配 tmp_abc，正则下 * 修饰的是前一个字符 _，反而匹配不到 tmp_abc',
    pattern      VARCHAR(256)  NOT NULL COMMENT '匹配表达式，不区分大小写',
    enabled      TINYINT       NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用。停用的规则不参与匹配',
    description  VARCHAR(512)  DEFAULT NULL COMMENT '说明',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_temp_rule_scope (tenant_id, project_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='临时库/表规则。命中的表在血缘图上会被穿透掉（上下游直接相连，而不是把图断成两段），保存血缘时一律过滤，库里永远不存临时表';
