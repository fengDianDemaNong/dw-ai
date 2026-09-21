# sql-tools

SQL 血缘分析服务端。接收一段 SQL，解析出**字段级血缘**，供前端 [sql-tools-vue](../sql-tools-vue) 渲染成血缘图。

## 设计说明

本服务负责串联解析、元数据、血缘合并与 HTTP。解析能力来自独立仓库（Maven 依赖，不在本仓库源码内）：

- https://gitee.com/songbaibuxiu/superior-sql-parser.git — 多方言拆句、DDL 元数据、表级血缘
- https://gitee.com/songbaibuxiu/sqlflow.git — 列级血缘语义分析

```
SQL 文本
  │
  ├─ 多方言解析     拆语句，识别 CreateTable / InsertTable / CTAS / Merge，提取 DDL 表结构
  ├─ 列级语义分析   对 DML 产出血缘（需要表结构，用于展开 select * 和无前缀列名）
  └─ SQLLineageMerger  合并成图：层级、同层序号、环检测、前端契约 JSON
```

## 环境要求

- **JDK 21**
- Maven 3.8+

## 本地开发（IDEA）

在 IDEA 里打开 `org.qq.Main` 直接 Run 即可，**不必先执行 `init-db.sh`**。

默认激活 `dev` profile：空的 H2 库会在启动时由 Flyway 自动执行
`db/migration/h2/V1__schema.sql` 和 `V2__init_data.sql`，写入默认租户/项目。启动成功后：

| 地址 | 说明 |
|---|---|
| http://localhost:8080 | 后端（安装包形态下同时托管前端） |
| http://localhost:8080/swagger-ui.html | 接口文档 |
| http://localhost:8080/actuator/health | 健康检查 |

命令行等价于：

```bash
mvn spring-boot:run
```

### 数据库怎么配

改库只动 [`src/main/resources/application-dev.yml`](src/main/resources/application-dev.yml)，
**不必在 IDEA Run Configuration 里填环境变量**。

默认是内嵌 H2，数据文件在模块工作目录下的 `data/dw_lineage`
（IDEA 工作目录一般是 `sql-tools/`）。

改连 MySQL / PostgreSQL 时，打开该文件，取消对应注释并填连接信息。
库本身要先建好；空库由 Flyway 自动建表。

```yaml
# application-dev.yml 摘录
database:
  # type: mysql
  # host: localhost
  # port: 3306
  # name: dw_lineage
  # username: root
  # password: root
```

| 库状态 | 启动时做什么 |
|---|---|
| 空库 | Flyway 依次执行 `db/migration/<方言>/V1__schema.sql` + `V2__init_data.sql` |
| 用安装包脚本建好的老库 | Flyway `baseline` 接管（记到第 2 版），之后应用 V3+ 新迁移 |
| 只执行了部分迁移 | Flyway 按版本继续补齐 |
| 迁移脚本校验和不一致 | 拒绝启动并指出是哪个版本 |

改了表结构后，在 `db/migration/<方言>/` 新增 `V3__xxx.sql`（三套方言都要写，
并与 `release/sql/` 保持同步，见 `release/sql/README.md`）。

生产安装包走外部 `conf/application.yml`，Flyway 同样开启：
空库启动即自动建表；已用 `bin/init-db.sh` 建好的库 baseline 接管，无需再手工升级。

### 打 jar 再跑

```bash
mvn clean package -DskipTests
java -jar target/sql-tools-1.0-SNAPSHOT.jar
```

不指定 `-Dspring.config.location` 时同样走 classpath 里的 `dev` 配置，空 H2 会自动建表。

## 测试

```bash
mvn test                    # 默认跳过依赖外部服务的用例
mvn test -Pexternal-tests   # 连同需要 Gravitino 服务的用例一起跑
```

> `gravitino` 包下的测试需要可访问的 Gravitino 服务（地址见 `application-dev.yml`）。
> 服务不可达时每个用例都要等网络超时，整包耗时 8 分钟以上，因此默认排除。
>
> 测试自己用 `TestSchema` 建表；Spring 上下文启动时 Flyway 见到非空库只会
> baseline，不会重复建表。

## API

### `GET /api/dbType`

返回支持的方言列表（仅名称）。

```json
["presto","trino","flink","spark","hive","starrocks","doris","redshift","mysql","oracle","postgres","sqlserver","ck"]
```

### `POST /api/lineage/analyze`

解析字段级血缘。

```json
{
  "dbType": "hive",
  "querySql": "create table ods.users(id int, name string);\ninsert into dws.stat select id, name from ods.users;",
  "columnName": null,
  "isCreateTable": true
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `dbType` | 是 | 方言，取值见 `/api/dbType` |
| `querySql` | 是 | SQL 文本，可含多条语句；上限 200,000 字符 |
| `columnName` | 否 | 只看某一列。支持裸列名 `id`，或全限定名 `dws.stat.id` |
| `isCreateTable` | 否 | 是否用 SQL 中的 `CREATE TABLE` 作为表结构元数据来源 |

响应：

```json
{
  "code": 0,
  "data": {
    "withProcessData": { "data": [ ... ], "size": 2, "level": 1 },
    "noProcessData":   { "data": [ ... ], "size": 1, "level": 0 }
  },
  "warnings": [],
  "failedStatements": [
    { "index": 2, "sql": "insert into dws.bad select * from nowhere.missing",
      "message": "SELECT * not allowed from relation that has no columns" }
  ],
  "unresolvedTables": [],
  "message": "",
  "traceId": "89ca1d60f1b34e2a"
}
```

- `withProcessData` —— 保留全部中间过程（临时表、子查询）
- `noProcessData` —— 压平中间过程，最终产出字段直连根源字段
- `warnings` —— 环依赖、元数据来源不可用等提示。**结果为空时优先看这里**
- `failedStatements` —— **部分失败容忍**：单条语句失败不影响其余语句，
  这里给出失败语句的序号（从 1 开始）、SQL 片段与原因
- `unresolvedTables` —— 结构未知的表，`select *` 等场景会因此缺列
- 每个字段节点含 `fieldName` / `level` / `index` / `final`；落在环上的边额外带 `cyclic: true`

层级语义：最终产出字段为 `level 0`，每向上游一跳 +1，取**最长路径**。

> 二期起 `errno` / `error` / `request_id` 已由 `message` / `traceId` 取代；
> `code` 与 `data` 的结构保持不变。

### `GET /api/dialects`

返回方言及其能力位。**部分方言无法从 SQL 内的 `CREATE TABLE` 提取表结构**，
必须依赖外部元数据服务：

```json
[
  { "type": "hive",  "columnLevelLineage": true, "ddlMetadataSupported": true,  "note": "" },
  { "type": "trino", "columnLevelLineage": true, "ddlMetadataSupported": false,
    "note": "该方言的 CREATE TABLE 无法提取表结构，请配置外部元数据服务；..." }
]
```

实测结论（见 `DialectCapabilityTest`）：

| 能力 | 结论 |
|---|---|
| 列级血缘分析 | 13 种方言在拿到表结构后**全部可用** |
| 从 SQL 内 DDL 提取表结构 | **trino / presto / sqlserver 不支持** |

### `POST /api/lineage/table`

表级血缘，只解析「哪张表写入、来自哪些表」。**不需要任何元数据**，
比列级血缘快一个数量级，可作为列级血缘因元数据缺失而失败时的降级方案。

```json
{ "dbType": "hive", "querySql": "insert into dws.t select a from ods.s" }
```

### `POST /api/sql/validate`

语法校验，返回带行列号的错误，可直接用于 Monaco 的 `setModelMarkers`。

```json
{ "valid": false, "errors": [{ "line": 3, "column": 1, "message": "mismatched input ..." }] }
```

### `GET /api/sql/keywords?dbType=spark`

该方言的关键字，供编辑器补全。

### `POST /api/mate/*`

对接 Gravitino 元数据服务，从远端读取表结构而非依赖 SQL 中的 DDL。
包含 `/matelakes`、`/catalogs`、`/schemas`、`/lineage/analyze`。

### 错误响应

```json
{
  "error": "SQL解析失败: ...",
  "message": "SQL解析失败: ...",
  "traceId": "a1b2c3d4e5f60718",
  "path": "/api/lineage/analyze",
  "timestamp": "2026-08-22T03:21:00Z"
}
```

服务内部错误只返回 `traceId`，详细堆栈仅记录在服务端日志，避免泄露内网地址与库表信息。
排查问题时提供 `traceId` 即可定位。

## 元数据从哪来

字段级血缘需要知道表结构（否则 `select *` 和不带表前缀的列名无法展开）。
元数据来源抽象为 `MetadataProvider` SPI，由 `CompositeMetadataProvider` 按优先级串联：

| 优先级 | Provider | 说明 |
|---|---|---|
| 1 | `DdlMetadataProvider` | SQL 里的 `CREATE TABLE`，请求带 `isCreateTable: true` |
| 2 | `GravitinoMetadataProvider` | 走 `/api/mate/lineage/analyze`，批量并发拉取；单表缺失只标记未解析，不再让整个请求失败 |
| 3 | `InferredMetadataProvider` | 从 SQL 结构推断，兜底 |

前一个解析不到的表才交给后一个；全都拿不到的表进入响应的 `unresolvedTables`。
新增元数据源（如 dbx、JDBC）只需实现该接口，不必改动解析主流程。

## 代码结构

解析主流程只有一条，元数据来源通过 SPI 注入：

```
MetadataServiceFactory   选方言、组装 MetadataProvider 及其优先级
        │
LineageAnalysisPipeline  唯一的解析流程：
        │                拆语句 → 挑 DML → 列级解析 → 批量补全元数据 → 产出血缘
        │
SQLLineageMerger         合并多语句：层级（Kahn 最长路径）、同层序号、环检测（Tarjan）
```

```
src/main/java/org/qq/
├── controller/       LineageController（血缘 / 方言 / SQL 校验）、GravitinoMetadataController
├── service/          LineageService、TableLineageService、SqlSyntaxService
│   ├── SqlParseExecutor        解析超时与栈溢出隔离
│   └── metadata/
│       ├── MetadataServiceFactory     方言选择 + Provider 组装
│       ├── LineageAnalysisPipeline    解析主流程
│       └── provider/                  MetadataProvider SPI 及各实现
├── util/             SQLLineageMerger、SqlUtils
├── dto/              请求/响应对象
├── enums/            DatabaseTypeEnum —— 方言注册表（解析器 + 能力位 + 关键字 + 语法校验）
├── exception/        SqlParseException、GlobalExceptionHandler
└── conf/             WebConfig、DataSourceConfig、DatabaseProperties、FlywayLocationEnvironmentPostProcessor
```

表结构由 Flyway 托管（与 dw-org / dw-model 一致）：迁移脚本在
`src/main/resources/db/migration/<方言>/`，目录由 `FlywayLocationEnvironmentPostProcessor`
按 `database.type` 选择；已用安装包脚本建好的老库通过 `baseline` 接管。

关键测试：

| 测试 | 覆盖 |
|---|---|
| `util/SQLLineageMergerCorrectnessTest` | 合并算法：层级、同层序号、环、确定性、深链路 |
| `lineage/LineageEndToEndTest` | 整条链路端到端，含部分失败容忍 |
| `dialect/DialectCapabilityTest` | 13 种方言能力矩阵，锁定能力位与实测一致 |
| `metadata/MetadataProviderTest` | SPI 优先级串联与降级 |
| `lineage/SqlParseExecutorTest` | 超时、栈溢出隔离 |
| `lineage/TableLineageAndSyntaxTest` | 表级血缘、语法校验、关键字 |
| `persistence/MigrationDialectTest` | 三套 DDL 一致性、升级脚本与全新安装结构一致 |

## 接口文档

启动后访问 <http://localhost:8080/swagger-ui.html>，文档由代码自动生成。

## 已知限制（上游缺口，非本项目可控）

- **trino / presto / sqlserver 的 `CREATE TABLE` 无法提取表结构**
  （被解析成 `DefaultStatement`），这三种方言必须配置外部元数据服务，
  否则 `select *`、聚合、列重命名等场景会缺列。能力位见 `GET /api/dialects`
- **ClickHouse 的 `sqlKeywords()` 未实现**，只返回一个占位符，关键字补全对 ck 不可用

> 一期遗留的 4 个失败用例已在二期全部修复。
