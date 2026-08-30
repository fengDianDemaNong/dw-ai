import type { EngineKind, KnowledgeSection, TenantKnowledgeArticle } from './types';

export type { KnowledgeSection };

export interface KnowledgeArticle {
  id: string;
  title: string;
  summary: string;
  body: string;
  sourceUrl: string;
  sourceLabel: string;
  sections: KnowledgeSection[];
  notes?: string[];
  imported?: boolean;
}

export interface EngineKnowledge {
  engine: EngineKind;
  label: string;
  vendor: string;
  hint: string;
  docsUrl: string;
  docsLabel: string;
  articles: KnowledgeArticle[];
}

export const ENGINE_OPTIONS: { value: EngineKind; label: string }[] = [
  { value: 'hive', label: 'Hive' },
  { value: 'spark', label: 'Spark' },
  { value: 'clickhouse', label: 'ClickHouse' },
  { value: 'doris', label: 'Doris' },
];

export const ALL_ENGINES: EngineKind[] = ENGINE_OPTIONS.map((e) => e.value);

export function engineLabel(engine: EngineKind) {
  return ENGINE_OPTIONS.find((e) => e.value === engine)?.label ?? engine;
}

export const ENGINE_KNOWLEDGE: EngineKnowledge[] = [
  {
    engine: 'hive',
    label: 'Hive',
    vendor: 'Apache Hive',
    hint: 'LanguageManual DDL：内部表 / 外部表、分区、MSCK。',
    docsUrl: 'https://hive.apache.org/docs/latest/language/languagemanual-ddl/',
    docsLabel: 'Hive LanguageManual DDL',
    articles: [
      {
        id: 'hive-create',
        title: '建表',
        summary: 'CREATE TABLE 语法、内部表与外部表、分区与存储格式。',
        body: '内容按 Apache Hive LanguageManual DDL 整理。CREATE TABLE 创建指定名称的表；同名表或视图已存在会报错，可用 IF NOT EXISTS 跳过。表名、列名不区分大小写，SerDe 与属性名区分大小写。',
        sourceUrl: 'https://hive.apache.org/docs/latest/language/languagemanual-ddl/',
        sourceLabel: 'hive.apache.org · LanguageManual DDL',
        sections: [
          {
            heading: '官方语法（节选）',
            body: 'Hive 0.14 起支持 TEMPORARY。EXTERNAL、PARTITIONED BY、CLUSTERED BY、ROW FORMAT、STORED AS、LOCATION、TBLPROPERTIES、AS SELECT 均可组合。CTAS 不支持外部表。',
            sqlCaption: 'CREATE TABLE',
            sql: `CREATE [TEMPORARY] [EXTERNAL] TABLE [IF NOT EXISTS] [db_name.]table_name
  [(col_name data_type [COMMENT col_comment], ...)]
  [COMMENT table_comment]
  [PARTITIONED BY (col_name data_type [COMMENT col_comment], ...)]
  [CLUSTERED BY (col_name, ...) [SORTED BY (col_name [ASC|DESC], ...)] INTO num_buckets BUCKETS]
  [ROW FORMAT row_format]
  [STORED AS file_format]
  [LOCATION hdfs_path]
  [TBLPROPERTIES (property_name=property_value, ...)]
  [AS select_statement];`,
          },
          {
            heading: '内部表与外部表',
            body: '不写 EXTERNAL 的是 managed table：Hive 管文件、元数据和统计。写 EXTERNAL 并指定 LOCATION 时，Hive 不使用默认仓库目录。删外部表默认不删文件系统上的数据。Hive 4.0.0（HIVE-19981）起，外部表可设 TBLPROPERTIES ("external.table.purge"="true")，DROP 时同时删数据。判断类型看 DESCRIBE EXTENDED 的 tableType。',
            sqlCaption: '官方 Tutorial / DDL 中的外部表示例',
            sql: `CREATE EXTERNAL TABLE page_view (
  viewTime INT,
  userid BIGINT,
  page_url STRING,
  referrer_url STRING,
  ip STRING COMMENT 'IP Address of the User',
  country STRING COMMENT 'country of origination'
)
COMMENT 'This is the staging page view table'
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\\054'
STORED AS TEXTFILE
LOCATION '<hdfs_location>';`,
          },
          {
            heading: '分区表',
            body: 'PARTITIONED BY 定义分区列。每个分区值组合对应一个独立数据目录。分区列是伪列，不要再写进普通列，否则会报 “Column repeated in partitioning columns”。表或分区还可 CLUSTERED BY 分桶，并用 SORTED BY 在桶内排序。',
            sqlCaption: '官方 page_view 分区表示例',
            sql: `CREATE TABLE page_view (
  viewTime INT,
  userid BIGINT,
  page_url STRING,
  referrer_url STRING,
  ip STRING COMMENT 'IP Address of the User'
)
COMMENT 'This is the page view table'
PARTITIONED BY (dt STRING, country STRING)
STORED AS SEQUENCEFILE;`,
            note: '默认字段分隔符是 ASCII 001（ctrl-A），行分隔符是换行。需要逗号分隔时写 ROW FORMAT DELIMITED FIELDS TERMINATED BY。',
          },
          {
            heading: '存储格式',
            body: '内置格式包括 TEXTFILE（默认，受 hive.default.fileformat 影响）、SEQUENCEFILE、RCFILE、ORC（0.11+，支持 ACID 与 CBO）、PARQUET（0.13+）、AVRO（0.14+）、JSONFILE（4.0+）。ORC 可用 TBLPROPERTIES ("orc.compress"="ZLIB"|"SNAPPY"|"NONE")。',
            sqlCaption: '仓储层常用写法',
            sql: `CREATE EXTERNAL TABLE IF NOT EXISTS dwd_trd_order_di (
  order_id STRING COMMENT '订单ID',
  user_id STRING COMMENT '用户ID',
  pay_amt DECIMAL(18,2) COMMENT '实付金额'
)
COMMENT '订单明细'
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/dw/dwd/trd/order_di';`,
          },
          {
            heading: 'CTAS 与 CREATE TABLE LIKE',
            body: 'CTAS 按查询结果建表并灌数，表对其他用户原子可见：要么看到完整结果，要么看不到表。Hive 3.2.0（HIVE-20241）起 CTAS 可写分区定义。CREATE TABLE … LIKE 只复制结构，可另指定 LOCATION。',
            sql: `CREATE TABLE student_copy
STORED AS PARQUET
AS SELECT * FROM student;

CREATE TABLE student_like LIKE student
LOCATION '/dw/tmp/student_like';`,
          },
        ],
        notes: [
          'Hive 约束（PRIMARY KEY / UNIQUE / CHECK）多为 DISABLE NOVALIDATE，不强制校验数据。',
          'TBLPROPERTIES ("transactional"="true") 从 0.14 起用于 ACID 表，默认 false。',
        ],
      },
      {
        id: 'hive-alter',
        title: '改表与改列',
        summary: 'RENAME、ADD/CHANGE/REPLACE COLUMNS、CASCADE。',
        body: 'ALTER TABLE 改表结构、SerDe、属性或表名。列变更默认只改元数据，不改文件内容；要保证实际数据布局与元数据一致。Hive 1.1.0 起可用 CASCADE 把列变更同步到全部分区元数据，默认 RESTRICT 只改表级元数据。',
        sourceUrl: 'https://hive.apache.org/docs/latest/language/languagemanual-ddl/',
        sourceLabel: 'hive.apache.org · Alter Table / Alter Column',
        sections: [
          {
            heading: '重命名与表属性',
            body: 'RENAME TO 改表名。Hive 2.2.0（HIVE-14909）起，内部表只有在未指定 LOCATION 且位于库目录下时，才会随改名移动 HDFS 路径。改注释要改 TBLPROPERTIES 的 comment。',
            sql: `ALTER TABLE old_table_name RENAME TO new_table_name;
ALTER TABLE table_name SET TBLPROPERTIES ('comment' = 'new comment');`,
          },
          {
            heading: 'CHANGE COLUMN',
            body: '可同时改列名、类型、注释、位置。PARTITION 子句从 0.14.0 起可用。CASCADE|RESTRICT 从 1.1.0 起可用。',
            sqlCaption: '官方 test_change 示例',
            sql: `CREATE TABLE test_change (a INT, b INT, c INT);

ALTER TABLE test_change CHANGE a a1 INT;
ALTER TABLE test_change CHANGE a1 a2 STRING AFTER b;
-- 结构变为：b int, a2 string, c int

ALTER TABLE test_change CHANGE c c1 INT FIRST;
-- 结构变为：c1 int, b int, a2 string

ALTER TABLE test_change CHANGE a1 a1 INT COMMENT 'this is column a1';`,
            note: 'CHANGE 只改 Hive 元数据，不会改底层文件。CASCADE 会覆盖各分区列元数据，与分区保护模式无关，需谨慎。',
          },
          {
            heading: 'ADD / REPLACE COLUMNS',
            body: 'ADD COLUMNS 把新列加到已有列末尾、分区列之前（Avro 表从 0.14 起也支持）。REPLACE COLUMNS 用新列集替换全部现有列，仅 native SerDe 可用；也可用来删列。',
            sql: `ALTER TABLE tab1 ADD COLUMNS (
  c1 INT COMMENT 'a new int column',
  c2 STRING
);

-- 只保留 a、b，相当于删掉 c
ALTER TABLE test_change REPLACE COLUMNS (a INT, b INT);`,
          },
        ],
        notes: [
          'Tutorial：给分区表加列后，旧分区读新列得到 NULL 或指定默认值。',
          'CLUSTERED BY / SORTED BY 的 ALTER 也只改元数据，不会重排已有文件。',
        ],
      },
      {
        id: 'hive-partition',
        title: '分区维护',
        summary: 'ADD / DROP PARTITION、MSCK REPAIR、EXCHANGE。',
        body: '分区可用 ALTER TABLE … PARTITION 增删、改名、交换。文件已直接落到 HDFS、元数据未登记时，用 MSCK REPAIR TABLE（或 ALTER TABLE RECOVER PARTITIONS）同步。',
        sourceUrl: 'https://hive.apache.org/docs/latest/language/languagemanual-ddl/',
        sourceLabel: 'hive.apache.org · Alter Partition / MSCK',
        sections: [
          {
            heading: '增删分区',
            body: 'ADD PARTITION 登记分区；DROP PARTITION 删除分区元数据。内部表默认会删对应目录；外部表是否删数据取决于配置与 external.table.purge。',
            sql: `ALTER TABLE dwd_trd_order_di ADD IF NOT EXISTS PARTITION (dt='2026-08-30');
ALTER TABLE dwd_trd_order_di DROP IF EXISTS PARTITION (dt='2026-01-01');`,
          },
          {
            heading: 'MSCK REPAIR TABLE',
            body: '默认 ADD PARTITIONS：把 HDFS 上有、metastore 没有的分区补进元数据。DROP PARTITIONS 删掉 HDFS 已无、元数据仍在的分区。SYNC PARTITIONS 等于两者都做。分区很多时设 hive.msck.repair.batch.size 分批，避免 OOM。不加 REPAIR 只报告不一致。',
            sql: `MSCK REPAIR TABLE table_name;
MSCK REPAIR TABLE table_name ADD PARTITIONS;
MSCK REPAIR TABLE table_name DROP PARTITIONS;
MSCK REPAIR TABLE table_name SYNC PARTITIONS;`,
            note: 'Hive 1.3 起，分区目录名含非法字符会抛错。客户端可用 hive.msck.path.validation=skip|ignore 调整。',
          },
          {
            heading: 'EXCHANGE PARTITION',
            body: 'HIVE-4095：把分区从源表挪到目标表并改双方元数据。多分区交换从 1.2.2 / 1.3.0 / 2.0.0+（HIVE-11745）起支持。两表 schema 与分区定义需一致。',
            sqlCaption: '官方 Exchange Partition 示例',
            sql: `CREATE TABLE T1 (a STRING, b STRING) PARTITIONED BY (ds STRING);
CREATE TABLE T2 (a STRING, b STRING) PARTITIONED BY (ds STRING);
ALTER TABLE T1 ADD PARTITION (ds='1');
ALTER TABLE T2 EXCHANGE PARTITION (ds='1') WITH TABLE T1;`,
          },
        ],
      },
      {
        id: 'hive-drop',
        title: '删表与截断',
        summary: 'DROP TABLE、TRUNCATE。外部表默认不删文件。',
        body: 'DROP TABLE 删表。内部表会删数据；外部表默认只删元数据。TRUNCATE TABLE 清空数据并保留表结构。',
        sourceUrl: 'https://hive.apache.org/docs/latest/language/languagemanual-ddl/',
        sourceLabel: 'hive.apache.org · Drop / Truncate Table',
        sections: [
          {
            heading: 'DROP / TRUNCATE',
            body: 'PURGE 可跳过回收站（视发行版与配置）。TRUNCATE 可按分区清空。TBLPROPERTIES ("auto.purge"="true")（1.2.0+，HIVE-9118）影响 DROP / DROP PARTITION / TRUNCATE / INSERT OVERWRITE 是否进回收站。',
            sql: `DROP TABLE IF EXISTS tmp_trd_order_stg;
TRUNCATE TABLE dwd_trd_order_di;
TRUNCATE TABLE dwd_trd_order_di PARTITION (dt='2026-01-01');`,
          },
        ],
      },
    ],
  },
  {
    engine: 'spark',
    label: 'Spark',
    vendor: 'Spark SQL',
    hint: '官方 SQL Reference：Data Source 建表与 ALTER TABLE。',
    docsUrl: 'https://spark.apache.org/docs/latest/sql-ref-syntax-ddl-create-table-datasource.html',
    docsLabel: 'Spark SQL · CREATE DATASOURCE TABLE',
    articles: [
      {
        id: 'spark-create',
        title: '建表',
        summary: 'USING 指定数据源；分区、分桶、CTAS。',
        body: '按 Spark SQL Guide「CREATE DATASOURCE TABLE」整理。CREATE TABLE 用 Data Source 定义新表。Data Source 表是指向底层数据的指针（例如 JDBC 指向 MySQL 表）。文件源（parquet、json 等）不写 LOCATION 时，Spark 会建默认表路径。',
        sourceUrl: 'https://spark.apache.org/docs/latest/sql-ref-syntax-ddl-create-table-datasource.html',
        sourceLabel: 'spark.apache.org · CREATE DATASOURCE TABLE',
        sections: [
          {
            heading: '官方语法',
            body: 'USING 与 AS SELECT 之间的子句顺序任意，例如 COMMENT 可以写在 TBLPROPERTIES 后面。省略 USING 时使用默认数据源（默认 parquet）。',
            sql: `CREATE TABLE [ IF NOT EXISTS ] table_identifier
    [ ( col_name1 col_type1 [ COMMENT col_comment1 ], ... ) ]
    USING data_source
    [ OPTIONS ( key1=val1, key2=val2, ... ) ]
    [ PARTITIONED BY ( col_name1, col_name2, ... ) ]
    [ CLUSTERED BY ( col_name3, col_name4, ... )
        [ SORTED BY ( col_name [ ASC | DESC ], ... ) ]
        INTO num_buckets BUCKETS ]
    [ LOCATION path ]
    [ COMMENT table_comment ]
    [ TBLPROPERTIES ( key1=val1, key2=val2, ... ) ]
    [ AS select_statement ]`,
          },
          {
            heading: '官方示例',
            body: 'USING 可选 CSV、TXT、ORC、JDBC、PARQUET 等。PARTITIONED BY 写列名（列需出现在表定义中），不要写成 Hive 那种 PARTITIONED BY (dt STRING)。CLUSTERED BY 按列分桶，减少 shuffle。',
            sqlCaption: 'Spark 4.x 文档示例',
            sql: `-- 指定数据源
CREATE TABLE student (id INT, name STRING, age INT) USING CSV;

-- 省略 USING，默认 parquet
CREATE TABLE student (id INT, name STRING, age INT);

-- CTAS
CREATE TABLE student_copy USING CSV
    AS SELECT * FROM student;

-- 注释与属性
CREATE TABLE student (id INT, name STRING, age INT) USING CSV
    COMMENT 'this is a comment'
    TBLPROPERTIES ('foo'='bar');

-- 分区 + 分桶
CREATE TABLE student (id INT, name STRING, age INT)
    USING CSV
    PARTITIONED BY (age)
    CLUSTERED BY (id) INTO 4 BUCKETS;

-- CTAS 分区分桶
CREATE TABLE student_partition_bucket
    USING parquet
    PARTITIONED BY (age)
    CLUSTERED BY (id) INTO 4 BUCKETS
    AS SELECT * FROM student;`,
          },
          {
            heading: 'CTAS 与 LOCATION',
            body: '带 LOCATION 的 CTAS：若路径已存在且非空目录，Spark 会分析失败。只有 spark.sql.legacy.allowNonEmptyLocationInCTAS=true 时才会用查询结果覆盖底层数据。',
            sqlCaption: '仓储表示例（列清单含分区列）',
            sql: `CREATE TABLE IF NOT EXISTS dwd_trd_order_di (
  order_id STRING COMMENT '订单ID',
  user_id STRING COMMENT '用户ID',
  pay_amt DECIMAL(18,2) COMMENT '实付金额',
  dt STRING
)
USING PARQUET
PARTITIONED BY (dt)
COMMENT '订单明细'
LOCATION '/dw/dwd/trd/order_di';`,
          },
        ],
        notes: [
          'Data Source 表一般是指针，要保证指向的数据存在；文件源例外，可不写 LOCATION。',
          'Hive 风格建表见官方「CREATE TABLE USING HIVE FORMAT」。',
        ],
      },
      {
        id: 'spark-alter',
        title: '改表',
        summary: 'RENAME、加减列、分区、属性、RECOVER PARTITIONS。',
        body: '按 Spark SQL Guide「ALTER TABLE」整理。ALTER TABLE 改表 schema 或属性。若表已缓存，多数 ALTER 会清掉该表及依赖对象的缓存，下次访问再懒加载。',
        sourceUrl: 'https://spark.apache.org/docs/latest/sql-ref-syntax-ddl-alter-table.html',
        sourceLabel: 'spark.apache.org · ALTER TABLE',
        sections: [
          {
            heading: '重命名表与分区',
            body: 'RENAME TO 只能在同一 database 内改名，不能跨库移动。分区可用 PARTITION (…) RENAME TO PARTITION (…)。分区值可用类型字面量，例如 date\'2019-01-02\'。',
            sql: `ALTER TABLE Student RENAME TO StudentInfo;
ALTER TABLE default.StudentInfo PARTITION (age='10')
  RENAME TO PARTITION (age='15');`,
          },
          {
            heading: '列操作',
            body: 'ADD COLUMNS 给已有表加列。DROP COLUMNS、RENAME COLUMN、REPLACE COLUMNS 仅支持 v2 表。ALTER / CHANGE COLUMN 改列定义（如注释）。',
            sqlCaption: '官方 StudentInfo 示例',
            sql: `ALTER TABLE StudentInfo ADD COLUMNS (LastName STRING, DOB TIMESTAMP);
ALTER TABLE StudentInfo DROP COLUMNS (LastName, DOB);
ALTER TABLE StudentInfo RENAME COLUMN name TO FirstName;
ALTER TABLE StudentInfo ALTER COLUMN FirstName COMMENT 'new comment';
ALTER TABLE StudentInfo REPLACE COLUMNS (name STRING, ID INT COMMENT 'new comment');`,
            note: 'v1 的 Parquet 托管表通常不能 DROP / RENAME / REPLACE COLUMN。需要删列时优先新表 + 切换，或改用 Iceberg / Delta 等 v2 表。',
          },
          {
            heading: '分区、属性、位置',
            body: 'ADD / DROP PARTITION 维护分区。SET / UNSET TBLPROPERTIES 改属性。SET LOCATION 改路径但不搬文件。SET FILEFORMAT 改格式。Hive 表可用 SET SERDE / SERDEPROPERTIES。RECOVER PARTITIONS 扫描目录并回写 Hive metastore，等价于 MSCK REPAIR TABLE。',
            sql: `ALTER TABLE StudentInfo ADD IF NOT EXISTS PARTITION (age=18);
ALTER TABLE StudentInfo ADD IF NOT EXISTS PARTITION (age=18) PARTITION (age=20);
ALTER TABLE StudentInfo DROP IF EXISTS PARTITION (age=18);

ALTER TABLE dbx.tab1 SET TBLPROPERTIES ('comment' = 'A table comment.');
ALTER TABLE dbx.tab1 UNSET TBLPROPERTIES ('winner');
ALTER TABLE dbx.tab1 PARTITION (a='1', b='2') SET LOCATION '/path/to/part/ways';
ALTER TABLE loc_orc SET FILEFORMAT orc;
ALTER TABLE dbx.tab1 RECOVER PARTITIONS;`,
          },
        ],
      },
      {
        id: 'spark-write',
        title: '写入与分区覆盖',
        summary: '动态分区覆盖、INSERT OVERWRITE。',
        body: 'Spark 默认 overwrite 覆盖整表。按分区覆盖需打开动态分区覆盖模式，并在写入时带上分区列。Iceberg / Delta 另有 MERGE INTO，本手册只覆盖内置 Spark SQL。',
        sourceUrl: 'https://spark.apache.org/docs/latest/sql-ref-syntax-dml-insert-table.html',
        sourceLabel: 'spark.apache.org · INSERT TABLE',
        sections: [
          {
            heading: '官方语法',
            body: 'INSERT 可 INTO（追加）或 OVERWRITE（覆盖）。覆盖可用 VALUES、另一张表或查询。官方说明：OVERWRITE 会替换表中已有数据。',
            sql: `INSERT [ INTO | OVERWRITE ] [ TABLE ] table_identifier [ partition_spec ] [ ( column_list ) | [BY NAME] ]
    { VALUES ( { value | NULL } [ , ... ] ) [ , ( ... ) ] | query }`,
          },
          {
            heading: '动态分区覆盖',
            body: 'spark.sql.sources.partitionOverwriteMode=dynamic 时，INSERT OVERWRITE 只替换本次写入涉及到的分区；未出现的分区保持不变。静态模式（默认）会按静态分区语义覆盖，未指定的分区可能被清空。官方 JavaDoc 将动态分区覆盖描述为与 Hive INSERT OVERWRITE … PARTITION 兼容，但更推荐用带过滤条件的覆盖。',
            sql: `SET spark.sql.sources.partitionOverwriteMode=dynamic;
INSERT OVERWRITE TABLE dwd_trd_order_di PARTITION (dt)
SELECT order_id, user_id, pay_amt, dt FROM stg_trd_order;`,
          },
          {
            heading: '官方 OVERWRITE 示例',
            body: '不带分区时，OVERWRITE 整表替换。也可用 INSERT OVERWRITE table TABLE other_table。',
            sqlCaption: 'Spark INSERT TABLE 文档',
            sql: `INSERT OVERWRITE students VALUES
    ('Ashua Hill', '456 Erica Ct, Cupertino', 111111),
    ('Brian Reed', '723 Kern Ave, Palo Alto', 222222);

INSERT OVERWRITE students TABLE visiting_students;

INSERT OVERWRITE students (address, name, student_id) VALUES
    ('Hangzhou, China', 'Kent Yao', 112112);`,
          },
        ],
      },
    ],
  },
  {
    engine: 'clickhouse',
    label: 'ClickHouse',
    vendor: 'ClickHouse',
    hint: 'MergeTree：ORDER BY 必填；ALTER 分元数据变更与 mutation。',
    docsUrl: 'https://clickhouse.com/docs/engines/table-engines/mergetree-family/mergetree',
    docsLabel: 'ClickHouse · MergeTree',
    articles: [
      {
        id: 'ck-create',
        title: '建表（MergeTree）',
        summary: 'ENGINE、ORDER BY、PARTITION BY、主键与粒度。',
        body: '按 ClickHouse 官方 MergeTree 文档整理。MergeTree 家族（含 ReplacingMergeTree、AggregatingMergeTree）是最常用引擎。插入写成 data part（已排序、压缩的小块），后台再 merge，因此叫 merge + tree。',
        sourceUrl: 'https://clickhouse.com/docs/engines/table-engines/mergetree-family/mergetree',
        sourceLabel: 'clickhouse.com · MergeTree table engine',
        sections: [
          {
            heading: '官方语法',
            body: 'ENGINE = MergeTree() 无参数。ORDER BY 是排序键；未写 PRIMARY KEY 时，排序键即主键。不需要排序可用 ORDER BY tuple()。PARTITION BY 可选。',
            sql: `CREATE TABLE [IF NOT EXISTS] [db.]table_name [ON CLUSTER cluster]
(
    name1 [type1] [[NOT] NULL] [DEFAULT|MATERIALIZED|ALIAS|EPHEMERAL expr1]
        [COMMENT ...] [CODEC(codec1)] [TTL expr1],
    name2 [type2] ...
)
ENGINE = MergeTree()
ORDER BY expr
[PARTITION BY expr]
[PRIMARY KEY expr]
[SAMPLE BY expr]
[TTL expr]
[SETTINGS name = value, ...]`,
          },
          {
            heading: 'ORDER BY 与 PARTITION BY',
            body: '主键决定每个 part 内的排序（聚簇索引），指向的是约 8192 行的 granule，而不是单行。官方建议：多数情况不需要分区；需要分区时粒度一般不要细过「按月」。分区不会像 ORDER BY 那样加速点查。不要按客户 ID / 姓名分区，应把它们放进 ORDER BY 最左。按月分区用 toYYYYMM(date_column)，分区名格式 YYYYMM。index_granularity 默认 8192，通常可省略。',
            sqlCaption: '官方文档中的子句组合示例',
            sql: `ENGINE MergeTree()
PARTITION BY toYYYYMM(EventDate)
ORDER BY (CounterID, EventDate, intHash32(UserID))
SAMPLE BY intHash32(UserID)
SETTINGS index_granularity = 8192`,
          },
          {
            heading: '数仓明细表示例',
            body: '官方 Quickstart 用 PARTITION BY toYYYYMM(date)、ORDER BY 业务过滤列。Nullable 少用；缺省值用 DEFAULT。需要合并去重时用 ReplacingMergeTree。',
            sql: `CREATE TABLE IF NOT EXISTS dwd_trd_order_di
(
    order_id String COMMENT '订单ID',
    user_id String COMMENT '用户ID',
    pay_amt Decimal(18, 2) COMMENT '实付金额',
    dt Date COMMENT '业务日期'
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, order_id)
SETTINGS index_granularity = 8192;`,
          },
        ],
        notes: [
          '旧版 CREATE TABLE MergeTree(date-column, sampling, (primary key), index_granularity) 已废弃，新项目不要用。',
          'ClickHouse Cloud 上写 ENGINE = MergeTree 可能被转成 SharedMergeTree，查询接口不变。',
        ],
      },
      {
        id: 'ck-alter',
        title: '改列',
        summary: 'ADD / DROP / RENAME / MODIFY COLUMN；加列很快，改类型会重写文件。',
        body: '按官方 ALTER TABLE … COLUMN 整理。一条语句可写多个逗号分隔动作。多数 ALTER 仅 *MergeTree、Merge、Distributed 表支持。改列是原子的；MergeTree 上也不加表级锁等完整停写（官方：lock-free）。',
        sourceUrl: 'https://clickhouse.com/docs/sql-reference/statements/alter/column',
        sourceLabel: 'clickhouse.com · ALTER TABLE … COLUMN',
        sections: [
          {
            heading: 'ADD COLUMN',
            body: '只改表结构，不立刻写旧数据。读旧 part 时按 DEFAULT 或零值 / 空串填充。列文件在 part merge 后才落盘，因此 ALTER 可以很快完成。AFTER / FIRST 指定位置。',
            sqlCaption: '官方 alter_test 示例',
            sql: `ALTER TABLE alter_test ADD COLUMN Added1 UInt32 FIRST;
ALTER TABLE alter_test ADD COLUMN Added2 UInt32 AFTER NestedColumn;
ALTER TABLE dwd_trd_order_di
  ADD COLUMN refund_amt Decimal(18, 2) DEFAULT 0 COMMENT '退款金额' AFTER pay_amt;`,
          },
          {
            heading: 'DROP / RENAME / COMMENT',
            body: 'DROP COLUMN 删整列文件，通常很快。被物化视图引用的列不能删。RENAME COLUMN 不改底层数据，几乎瞬时；ORDER BY / PRIMARY KEY 中的列不能改名（SQL Error [524]）。',
            sql: `ALTER TABLE visits DROP COLUMN browser;
ALTER TABLE visits RENAME COLUMN webBrowser TO browser;
ALTER TABLE visits COMMENT COLUMN browser 'This column shows the browser used for accessing the site.';`,
          },
          {
            heading: 'MODIFY COLUMN',
            body: '可改类型、默认表达式、CODEC、TTL、列级 SETTINGS。只改 DEFAULT 几乎瞬时。改类型会按 toType 转换并改数据文件，大表会很慢。主键列改类型仅当数据文件无需改写时才允许（例如给 Enum 加值，或 DateTime → UInt32）。',
            sql: `ALTER TABLE visits MODIFY COLUMN browser Array(String);
ALTER TABLE dwd_trd_order_di MODIFY COLUMN pay_amt Decimal(18, 4);
ALTER TABLE users MODIFY COLUMN c2 String FIRST;`,
            note: 'Nullable 改成 Non-Nullable 前必须确认没有 NULL，否则读会出问题。若已跑起来，应杀掉 mutation 并把列改回 Nullable。',
          },
          {
            heading: 'MATERIALIZE COLUMN',
            body: 'ADD COLUMN … MATERIALIZED 不会回填历史行。要用 MATERIALIZE COLUMN 按 DEFAULT / MATERIALIZED 表达式重写已有数据（实现为 mutation）。排序键中的列不能 MATERIALIZE。',
            sql: `ALTER TABLE tmp ADD COLUMN s String MATERIALIZED toString(x);
ALTER TABLE tmp MATERIALIZE COLUMN s;`,
          },
        ],
        notes: [
          '不能删主键或 sampling key 中的列。ALTER 不够用时：建新表 → INSERT SELECT → RENAME 切换 → DROP 旧表。',
          'Distributed 表的 ALTER 只改本地结构，远程节点上的表要分别 ALTER。',
        ],
      },
      {
        id: 'ck-partition',
        title: '分区维护',
        summary: 'DROP / DETACH / ATTACH / REPLACE PARTITION。',
        body: '按官方 ALTER TABLE … PARTITION 整理。分区表达式可写 system.parts 的 partition 值、tuple(...)、PARTITION ID \'...\'，或 ALL（限 DROP/DETACH/ATTACH）。',
        sourceUrl: 'https://clickhouse.com/docs/sql-reference/statements/alter/partition',
        sourceLabel: 'clickhouse.com · ALTER TABLE … PARTITION',
        sections: [
          {
            heading: 'DROP / DETACH / ATTACH',
            body: 'DROP PARTITION 把分区标为 inactive，大约 10 分钟后彻底删数据，复制表会在全部副本上删。DETACH 把数据挪到 detached 目录，服务器当作分区不存在，直到 ATTACH。',
            sql: `ALTER TABLE mt DROP PARTITION '2020-11-21';
ALTER TABLE mt DETACH PARTITION '2020-11-21';
ALTER TABLE visits ATTACH PARTITION 201901;`,
          },
          {
            heading: '跨表替换与移动',
            body: 'REPLACE PARTITION 从 table1 拷分区并原子替换 table2 中同名分区，源表数据不删。两表必须结构、分区键、ORDER BY、主键、存储策略一致。MOVE PARTITION TO TABLE 会从源表删掉该分区。',
            sql: `ALTER TABLE table2 REPLACE PARTITION partition_expr FROM table1;
ALTER TABLE dwd_trd_order_di
  REPLACE PARTITION '202608' FROM dwd_trd_order_di_stg;
ALTER TABLE table_source MOVE PARTITION partition_expr TO TABLE table_dest;`,
          },
          {
            heading: '分区内 UPDATE / DELETE',
            body: 'UPDATE / DELETE 是 mutation，会重写整个 data part。加 IN PARTITION 只处理指定分区，降低大表负担。进度看 system.mutations。',
            sql: `ALTER TABLE mt UPDATE x = x + 1 IN PARTITION 2 WHERE p = 2;
ALTER TABLE mt DELETE IN PARTITION 2 WHERE p = 2;`,
          },
        ],
        notes: [
          '分区名是否加引号取决于类型：String 要引号，Date / Int* 通常不要。',
          'FREEZE PARTITION 做本地硬链接备份，不复制；恢复时拷到 detached 再 ATTACH。',
        ],
      },
    ],
  },
  {
    engine: 'doris',
    label: 'Doris',
    vendor: 'Apache Doris',
    hint: 'DUPLICATE / UNIQUE / AGGREGATE；分区分桶建表时定。',
    docsUrl: 'https://doris.apache.org/docs/4.x/sql-manual/sql-statements/table-and-view/table/CREATE-TABLE/',
    docsLabel: 'Apache Doris · CREATE TABLE',
    articles: [
      {
        id: 'doris-create',
        title: '建表',
        summary: 'CREATE TABLE 语法、Key、分区、分桶、CTAS / LIKE。',
        body: '按 Apache Doris 4.x CREATE TABLE 手册整理。表可含多列；列可声明是否为 Key、聚合语义、生成列、NOT NULL、自增、插入默认值、更新默认值。也支持 CTAS 与 CREATE TABLE LIKE。',
        sourceUrl: 'https://doris.apache.org/docs/4.x/sql-manual/sql-statements/table-and-view/table/CREATE-TABLE/',
        sourceLabel: 'doris.apache.org · CREATE TABLE',
        sections: [
          {
            heading: '官方语法（节选）',
            body: 'Key 类型为 DUPLICATE（明细）、UNIQUE（主键）、AGGREGATE（聚合）。Key 列必须是表的前 K 列，单 tablet 内按这些列排序。ORDER BY（局部排序列）从 4.1.0 起支持，且仅 UNIQUE 模型可用。',
            sql: `CREATE [TEMPORARY | EXTERNAL] TABLE [IF NOT EXISTS] <table_name>
    (<columns_definition> [<indexes_definition>])
    [ENGINE = <table_engine_type>]
    [<key_type> KEY (<key_cols>) [ORDER BY (<cluster_cols>)]]
    [COMMENT '<table_comment>']
    [<partitions_definition>]
    [DISTRIBUTED BY { HASH (<distribute_cols>) | RANDOM }
        [BUCKETS { <bucket_count> | AUTO }]]
    [PROPERTIES ("<table_property>" [, ...])]`,
          },
          {
            heading: '分区分桶',
            body: '分区支持 RANGE / LIST，以及 AUTO PARTITION。RANGE 可用 VALUES LESS THAN、VALUES [下界, 上界)、FROM … TO … INTERVAL。明细模型分桶列可以是任意列；聚合模型与主键模型的分桶列必须与 Key 列一致。桶数是正整数，也可 AUTO。',
            sql: `CREATE TABLE IF NOT EXISTS dwd_trd_order_di (
  dt DATE COMMENT '业务日期',
  order_id VARCHAR(64) COMMENT '订单ID',
  user_id VARCHAR(64) COMMENT '用户ID',
  pay_amt DECIMAL(18,2) COMMENT '实付金额'
)
DUPLICATE KEY(dt, order_id)
PARTITION BY RANGE(dt) (
  PARTITION p202608 VALUES [('2026-08-01'), ('2026-09-01'))
)
DISTRIBUTED BY HASH(order_id) BUCKETS 16
PROPERTIES (
  "replication_num" = "3"
);`,
          },
          {
            heading: 'CTAS 与 LIKE',
            body: 'CTAS 按查询结果建表并灌数。LIKE 只复制列定义与属性，不复制数据；可 WITH ROLLUP 复制指定 rollup。',
            sql: `CREATE TABLE student_copy
DUPLICATE KEY(id)
DISTRIBUTED BY HASH(id) BUCKETS 8
AS SELECT * FROM student;

CREATE TABLE new_table LIKE existing_table;`,
          },
        ],
        notes: [
          '标识符以字母开头，不能用保留字；含空格或特殊字符时要用反引号。',
          '建表后改分桶数、改 Key 结构成本高，通常要建新表。',
        ],
      },
      {
        id: 'doris-model',
        title: '数据模型',
        summary: 'Duplicate / Unique / Aggregate 的适用场景与写法。',
        body: 'Doris 提供三种存储模型。模型在建表时选定。Unique 的 merge-on-write / merge-on-read 也只能建表时定，之后不能用 schema change 改。',
        sourceUrl: 'https://doris.apache.org/docs/4.x/table-design/data-model/duplicate',
        sourceLabel: 'doris.apache.org · Data Model',
        sections: [
          {
            heading: 'Duplicate Key（默认明细）',
            body: '保留每一条原始记录，不去做重、不做聚合。DUPLICATE KEY 只指定排序列以优化常见查询，不要求唯一。官方建议排序列一般不超过 3 个。适合日志、用户行为、结束后不再改的交易明细。',
            sqlCaption: '官方 example_tbl_duplicate',
            sql: `CREATE TABLE IF NOT EXISTS example_tbl_duplicate (
    log_time        DATETIME       NOT NULL,
    log_type        INT            NOT NULL,
    error_code      INT,
    error_msg       VARCHAR(1024),
    op_id           BIGINT,
    op_time         DATETIME
)
DUPLICATE KEY(log_time, log_type, error_code)
DISTRIBUTED BY HASH(log_type) BUCKETS 10;`,
            note: '相同行再 INSERT 一次会原样追加，查询能看到重复行。',
          },
          {
            heading: 'Unique Key（主键）',
            body: '按 Key 保持唯一：插入或更新时，新行覆盖同 Key 旧行。适合 OLTP 维表实时同步、按用户 ID 去重、部分列更新。默认开启 merge-on-write。分区键必须是 Key 列的子集，才能保证唯一。',
            sqlCaption: '官方 example_tbl_unique',
            sql: `CREATE TABLE IF NOT EXISTS example_tbl_unique (
    user_id         LARGEINT        NOT NULL,
    user_name       VARCHAR(50)     NOT NULL,
    city            VARCHAR(20),
    age             SMALLINT,
    sex             TINYINT
)
UNIQUE KEY(user_id, user_name)
DISTRIBUTED BY HASH(user_id) BUCKETS 10;`,
            note: '整行 UPSERT 时，INSERT 只写部分列，其余列会被填成 NULL 或默认值。部分列更新需要 merge-on-write，并单独打开参数。',
          },
          {
            heading: 'Aggregate Key（预聚合）',
            body: '导入和后台 Compaction 阶段按 Key 预聚合，只存聚合结果。适合明细汇总、不依赖原始明细的报表。Value 列必须声明聚合类型：SUM、REPLACE、MAX、MIN、REPLACE_IF_NOT_NULL、HLL_UNION、BITMAP_UNION。',
            sqlCaption: '官方 example_tbl_agg',
            sql: `CREATE TABLE IF NOT EXISTS example_tbl_agg (
    user_id             LARGEINT    NOT NULL,
    load_date           DATE        NOT NULL,
    city                VARCHAR(20),
    last_visit_dt       DATETIME    REPLACE DEFAULT "1970-01-01 00:00:00",
    cost                BIGINT      SUM     DEFAULT "0",
    max_dwell           INT         MAX     DEFAULT "0"
)
AGGREGATE KEY(user_id, load_date, city)
DISTRIBUTED BY HASH(user_id) BUCKETS 10;`,
          },
        ],
      },
      {
        id: 'doris-alter',
        title: '改列',
        summary: 'ADD / DROP / MODIFY / ORDER BY；异步任务与 light schema change。',
        body: '按官方 ALTER TABLE COLUMN 整理。Schema change 是异步的：提交成功即返回，进度用 SHOW ALTER TABLE COLUMN 查看。Doris 1.2.0 起支持 light schema change；2.0.0 及以后默认开启。加减 Value 列可以更快、同步完成。建表时可写 "light_schema_change"="true"。',
        sourceUrl: 'https://doris.apache.org/docs/4.x/sql-manual/sql-statements/table-and-view/table/ALTER-TABLE-COLUMN/',
        sourceLabel: 'doris.apache.org · ALTER TABLE COLUMN',
        sections: [
          {
            heading: '加列 / 删列 / 改列 / 重排',
            body: '可指定位置（AFTER / FIRST）和目标索引（基表或 rollup）。聚合模型加 Value 列要带聚合类型。',
            sql: `ALTER TABLE dwd_trd_order_di
  ADD COLUMN refund_amt DECIMAL(18,2) DEFAULT "0" COMMENT "退款金额";

ALTER TABLE example_db.my_table
  ADD COLUMN new_col INT KEY DEFAULT "0" AFTER existing_col;

ALTER TABLE example_db.my_table
  DROP COLUMN col_name;

ALTER TABLE example_db.my_table
  MODIFY COLUMN col_name BIGINT NULL DEFAULT "0" AFTER other_col;

ALTER TABLE example_db.my_table
  ORDER BY (k1, k2, v1);`,
          },
          {
            heading: 'Rollup',
            body: '查询加速可加 ROLLUP，不要把所有维度都塞进基表 KEY。',
            sql: `ALTER TABLE dwd_trd_order_di
  ADD ROLLUP r_user (dt, user_id, pay_amt);`,
          },
        ],
        notes: [
          '改 Key 结构、改分桶通常不能靠一次轻量 ALTER 完成，要建新表迁移。',
          'DELETE 按谓词删行；大范围过期数据优先 DROP PARTITION。',
        ],
      },
      {
        id: 'doris-partition',
        title: '分区维护',
        summary: 'ADD / DROP / MODIFY PARTITION。同步执行。',
        body: '按官方 ALTER TABLE PARTITION 整理。该操作是同步的，命令返回即完成。建表时若未显式建分区，之后不能用 ALTER 加分区。分区表至少保留一个分区。',
        sourceUrl: 'https://doris.apache.org/docs/4.x/sql-manual/sql-statements/table-and-view/table/ALTER-TABLE-PARTITION/',
        sourceLabel: 'doris.apache.org · ALTER TABLE PARTITION',
        sections: [
          {
            heading: '加分区',
            body: 'partition_desc 两种写法：VALUES LESS THAN [MAXVALUE|("value")]，或 VALUES [("下界"), ("上界"))。区间左闭右开；只写右边界时系统自动推左边界。不指定分桶则沿用建表时的分桶方式和桶数。指定了分桶方式就只能改桶数，不能改分桶列。',
            sqlCaption: '官方示例',
            sql: `-- 已有 [MIN, 2013-01-01)，追加 [2013-01-01, 2014-01-01)
ALTER TABLE example_db.my_table
  ADD PARTITION p1 VALUES LESS THAN ("2014-01-01");

ALTER TABLE example_db.my_table
  ADD PARTITION p1 VALUES [("2014-01-01"), ("2014-02-01"));

ALTER TABLE example_db.my_table
  ADD PARTITION p1 VALUES LESS THAN ("2015-01-01")
  DISTRIBUTED BY HASH(k1) BUCKETS 20;

ALTER TABLE dwd_trd_order_di
  ADD PARTITION p202609 VALUES [('2026-09-01'), ('2026-10-01'));`,
          },
          {
            heading: '删分区与改属性',
            body: 'DROP PARTITION 后一段时间内可用 RECOVER 找回。FORCE 不检查未完成事务且不可恢复，一般不建议。MODIFY PARTITION 可改 storage_medium、storage_cooldown_time、replication_num、in_memory。单分区表的分区名等于表名。',
            sql: `ALTER TABLE example_db.my_table DROP PARTITION p1;
ALTER TABLE example_db.my_table
  DROP PARTITION p1, DROP PARTITION p2, DROP PARTITION p3;

ALTER TABLE example_db.my_table
  MODIFY PARTITION p1 SET ("replication_num"="1");
ALTER TABLE example_db.my_table
  MODIFY PARTITION (p1, p2, p4) SET ("replication_num"="1");
ALTER TABLE example_db.my_table
  MODIFY PARTITION (*) SET ("storage_medium"="HDD");`,
          },
        ],
      },
    ],
  },
];

export function knowledgeOf(engine: EngineKind) {
  return ENGINE_KNOWLEDGE.find((k) => k.engine === engine);
}

function asArticle(row: TenantKnowledgeArticle): KnowledgeArticle {
  return {
    id: row.id,
    title: row.title,
    summary: row.summary,
    body: row.body,
    sourceUrl: row.sourceUrl || '',
    sourceLabel: row.sourceLabel || '',
    sections: row.sections ?? [],
    notes: row.notes,
    imported: true,
  };
}

/** 内置手册 + 本租户导入篇。同 engine + 同 id 用导入覆盖展示。 */
export function mergeEngineKnowledge(engines: EngineKind[], imported: TenantKnowledgeArticle[]) {
  return ENGINE_KNOWLEDGE.filter((k) => engines.includes(k.engine)).map((pack) => {
    const extra = imported.filter((a) => a.engine === pack.engine);
    const overlay = new Map(extra.map((a) => [a.id, a]));
    const articles = pack.articles.map((a) => {
      const hit = overlay.get(a.id);
      if (!hit) return a;
      overlay.delete(a.id);
      return asArticle(hit);
    });
    for (const row of extra) {
      if (overlay.has(row.id)) articles.push(asArticle(row));
    }
    return { ...pack, articles };
  });
}
