# 架构说明

## 整体数据流

```
                    SQL 文本
                       │
        ┌──────────────▼──────────────┐
        │   多方言解析器               │  按方言拆分语句
        │   DatabaseTypeEnum.parse()   │  识别 CreateTable / InsertTable / CTAS / Merge
        └──────────────┬──────────────┘  提取 DDL 中的表结构
                       │
                       │  只挑出会产生血缘的写入语句
                       │  （insert / create table as select / merge）
                       │
        ┌──────────────▼──────────────┐
        │   SqlFlowParser              │  SQL → AST
        └──────────────┬──────────────┘
                       │
        ┌──────────────▼──────────────┐
        │   MetadataProvider            │  批量补全表结构
        │   DDL > 外部服务 > 结构推断    │  查不到的进 unresolvedTables
        └──────────────┬──────────────┘
                       │
        ┌──────────────▼──────────────┐
        │   StatementAnalyzer          │  语义分析，产出每条语句的列级血缘
        │   analyzeMultipleDetailed()  │  单条失败不影响其余，失败原因一并返回
        └──────────────┬──────────────┘
                       │
        ┌──────────────▼──────────────┐
        │   SQLLineageMerger            │  合并多条语句成一张图
        │   Kahn 拓扑排序 → 层级         │  Tarjan → 环检测
        └──────────────┬──────────────┘
                       │
                  LineageGraph  ──→  HTTP 响应 / 持久化
```

## 后端分层

```
controller/          HTTP 入口，DTO 校验
    │
service/
    ├── LineageService          列级血缘
    ├── TableLineageService     表级血缘（不需元数据）
    ├── SqlSyntaxService        语法校验、关键字
    ├── SqlParseExecutor        解析超时与栈隔离
    └── metadata/
         ├── MetadataServiceFactory     选方言、组装 Provider
         ├── LineageAnalysisPipeline    唯一的解析主流程
         └── provider/                  MetadataProvider SPI
    │
persistence/         血缘持久化（版本、图遍历）
    │
util/SQLLineageMerger    血缘合并算法
```

---

## 关键设计决策

### 为什么需要两个解析器

它们的粒度不同，互补：

| | 多方言解析 | 列级语义分析 |
|---|---|---|
| 粒度 | 语句类型、**表级**血缘、DDL 元数据 | **列级**血缘 |
| 语法 | 每种数据库一套官方语法 | 一套 trino 派生语法 |
| 需要元数据 | 不需要 | **需要**（展开 `select *`） |

典型用法：先拆分脚本、拿到 `CREATE TABLE` 的字段信息，
再把 `insert` / `ctas` 语句交给列级分析。

### 解析主流程只有一份

早期每种元数据来源都复制一份完整流程（共四份约 360 行），新增来源要改主流程。
现在收敛为 `LineageAnalysisPipeline` 一份，元数据来源通过 `MetadataProvider` 注入：

```java
public interface MetadataProvider {
    String name();
    MetadataResolution resolve(Set<QualifiedObjectName> tables);
}
```

`CompositeMetadataProvider` 按优先级串联，实现约定两条：
**批量**（一次传入全部待解析的表，避免逐表往返）、
**不抛异常**（查不到属正常情况，放进 `unresolved`）。

### 层级为什么用 Kahn 拓扑排序

层级语义是「最终产出字段为 0，每向上游一跳 +1，取最长路径」。

早期实现用 BFS + `visited` 集合，节点被抬高层级后不会重新入队，
新层级无法向下游传播。更糟的是起始队列来自 `HashMap.keySet()`，
遍历顺序不确定，**同一份 SQL 两次解析可能得到不同的图**。

现在用 Kahn 拓扑排序按序松弛，入队顺序按字典序固定，结果与输入顺序无关。

### 环的处理

`insert into t select ... from t` 这类语句会产生环。处理方式：

1. Tarjan 强连通分量检测（迭代实现，避免深链路栈溢出）
2. 环上字段写入 `warnings`，环上的边标记 `cyclic: true`，前端可用虚线渲染
3. 层级计算遇环强制断开回边，不会死循环

### 目标列与 select 列表按位置对应

SQL 语义是位置对应：`insert into t(a,b) select x,y` 映射 `x→a`、`y→b`，
**与列名是否相同无关**。

早期列级分析用列名严格匹配，只要 ETL 中重命名了列（极其常见），
整条语句的血缘就被丢弃。已改为 `Field.matchFieldsByPosition()`。
数量不匹配时多余目标列给空上游，而不是让整条语句失败。

### 解析为什么放到独立线程

`SqlParseExecutor` 提供两层保护：

- **超时**：ANTLR 在病态 SQL 上可能长时间回溯，占住 web 线程
- **栈隔离**：深嵌套 SQL 触发 `StackOverflowError`，它是 `Error` 不是 `Exception`，
  发生在 web 线程上后果不可控。放到专用线程后只会终止该线程，并转换成正常业务异常；
  这些线程还配置了更大的栈（默认 16MB）

> **注意**：这带来一个副作用 —— `ThreadLocal` 不会自动传递到解析线程。
> 租户上下文必须用 `TenantContextHolder.wrap()` 显式透传，`SqlParseExecutor` 内部已统一处理。

### 部分失败容忍

多语句脚本中单条失败不应影响其余。`StatementAnalyzer.analyzeMultipleDetailed()`
返回成功结果 + 失败明细，一路透出到响应的 `failedStatements`。

早期实现是 `catch (Exception e) { System.err.println(...) }`，
调用方拿不到任何信息，无法区分「没有可解析的语句」与「语句解析失败被跳过」，
所有缺陷都表现为「接口 200、图是空的、不知道为什么」。

---

## 持久化设计（三期）

### 数据模型

```
tenant
  ├── metadata_source           元数据服务配置（租户级共享，各项目通用）
  └── project
        ├── data_catalog        数据目录，其中恰好一条 is_default=1
        ├── temp_rule           临时库/表规则
        ├── meta_table          本地元数据目录
        │     └── meta_column
        └── lineage_version     血缘版本
              ├── lineage_table
              ├── lineage_column
              └── lineage_edge
```

所有业务表带 `tenant_id` + `project_id`，`metadata_source` 例外 —— 它只带 `tenant_id`。

**表全名恒为三段 `数据目录.库.表`。** 数据目录参与唯一性：
`hive_prod.ods.orders` 与 `hive_test.ods.orders` 是两张不同的表。
建表语句里没写目录时落到该项目的默认目录，因此不存在「没有数据目录」的表。

### 几个取舍

**边表存 `col_id` 而非列全名** —— 图遍历要反复 join，整型主键比 700 字符的字符串快得多。

**保留 `full_name` 冗余字段** —— 「按名字查血缘」是最高频入口，
有它就能一次命中索引，不必先解析再多表 join。

**不做外键约束** —— 删除版本要级联删几十万行，外键会显著拖慢；
三种数据库的级联行为也不完全一致，应用层按 `version_id` 批量删更可控。

**不建表级边表** —— 表级血缘可由列级边聚合得到，单独存会引入一致性问题。

### 多数据库

| 场景 | 选型 |
|---|---|
| 本地/嵌入式 | H2 2.x（file 模式），默认 |
| 生产 | MySQL 8.0+ / PostgreSQL 12+ |

图遍历用递归 CTE，三种数据库语法一致，共用同一份 SQL。
**MySQL 必须 8.0+**，5.7 不支持递归 CTE。

建表与升级由 Flyway 托管（与 dw-org / dw-model 一致）：空库启动自动执行迁移并灌初始数据，
已用安装包脚本建好的库 `baseline` 接管、不重复建表。
迁移脚本按方言分目录（`api/src/main/resources/db/migration/{h2,mysql,postgresql}`），
安装包里的 `release/sql/{h2,mysql,postgresql}` 与之内容完全一致，供离线手工安装 / DBA 审阅。
`release/sql/upgrade/` 保留离线升级脚本，供无外网 / 停机窗口手工执行。
三个后端共用同一份测试用例；MySQL / PostgreSQL 由 `MigrationExternalDbIT` 在真实库上验证，
既验全新安装的结构，也验升级脚本对存量数据的改写。

### 临时表过滤：穿透而不是删点

ETL 的 SQL 为了完成任务会建一堆中间临时表。哪些算临时由 `temp_rule` 配置
（库名 / 表名 × 通配符 / 正则）。过滤的语义是**穿透**：

```
解析结果        source_a → tmp.bb → sink_b
过滤后          source_a → sink_b          ← 不是把 tmp.bb 删掉留两段断开的图
```

实现是对边表做一次变换（`TempTableFilter`），沿上游做传递闭包直到遇上非临时字段，
链式临时表 `A → t1 → t2 → B` 一路穿到底，临时节点成环时靠 visited 集合停下来。

两条路径共用同一个实现：

| 场景 | 行为 |
|---|---|
| 血缘分析页（即席解析） | 「包含临时表」开关，**默认不含** —— 所见即所存 |
| 保存血缘 | **强制过滤**，不受开关影响。库里永远没有临时表 |
| 血缘关系页（查已存的） | 不给开关 —— 库里本来就没有临时表，给了是误导 |

> 匹配方式必须显式选，因为两者对同一个表达式的解释可能完全相反：
> `tmp_*` 在通配符下匹配 `tmp_abc`，在正则下 `*` 修饰的是前一个字符 `_`，
> 匹配的是 `tmp` / `tmp_` / `tmp__`，**唯独匹配不到 `tmp_abc`**。
> 写错了不报错、只是默默匹配不上，所以配置页提供了当场试算的入口。

### 多语句分析：边分析边登记

`create table tmp.bb as select ...` 之后紧跟 `insert ... from tmp.bb` 是 ETL 常见写法，
但列级分析两头都堵：预先给 `tmp.bb` 喂结构，CTAS 会报 `Destination table already exists`
把整条语句丢掉；不喂，后面的 `insert` 又报 `metadata not exists`。

因此 `LineageAnalysisPipeline` 不用 `analyzeMultipleDetailed` 一次跑完，而是**逐条分析**，
每分析完一条 CTAS 就把它现建出来的表登记进元数据服务再分析下一条 ——
这也正是这段 SQL 真实的执行顺序。逐条构造 `StatementAnalyzer` 与该方法内部的做法一致，
没有额外开销。

### 租户隔离

选的是「共享表 + tenant_id 字段」，风险是某个查询忘了带租户条件导致越权。
三层保障：

1. `TenantContextHolder` 提供上下文，缺失时**直接抛异常而非静默用默认值**
2. `LineageRepository` 每个方法第一个参数固定为 `LineageContext`，从签名上强制传入
3. 所有原生 SQL 收敛在 `JdbcLineageRepository` 内，配合架构测试静态检查

验收要求包含**跨租户越权的负向用例**，不能只测正向路径。
