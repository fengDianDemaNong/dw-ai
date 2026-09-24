# dw-lineage（数据地图）

由旁边仓库 `sql-lineage` 拷入本仓库，目录名改为 `dw-lineage`。目录名已与 dw-org / dw-model 统一为 `ui/` + `api/`；内部工程名（pom 的 artifactId、npm 包名）仍是 `sql-tools`。智仓侧栏 iframe 嵌本模块（默认 `http://127.0.0.1:5173`）。

输入一段 SQL，解析出**字段级血缘**（哪个字段来自哪个字段）与**表级血缘**，并渲染成可交互的血缘图。

```
create table ods.users(id int, name string);
create table dws.stat(user_id int, user_name string);
insert into dws.stat select id, name from ods.users;
```

解析结果：

```
dws.stat.user_id   ←  ods.users.id
dws.stat.user_name ←  ods.users.name
```

---

## 仓库构成

本仓库是应用层：后端、前端、安装包脚本。

```
dw-lineage/
├── api/                   后端服务（Spring Boot 3.3，JDK 21）
├── ui/                    前端（Vue 3.5 + Vite 6 + AntV G6 + Monaco）
├── packaging/             安装包模板（bin / conf）
├── release/               打包产物输出
├── package.sh             打安装包
└── ci.sh                  应用层校验
```

在仓库根目录也可按模式启动（三种模式说明见仓库根 [README.md](../README.md)）：

```bash
# —— 多租户 multi（嵌入组织 / 仓建设，端口 18082 / 5173）
# multi 必须与组织、仓建设配**同一个** MODULE_TOKEN，否则 /internal/v1/** 一律 401，
# 项目镜像同步不过来，页面表现是「租户编码未同步」。
MODULE_TOKEN=<与 org 相同的值> npm run dev:api:lineage     # sql-tools
npm run dev:lineage         # multi 模式，等价于 npm run dev:multi -w sql-tools

# —— 普通 standard（默认，本模块账号，端口 8080 / 5173）
mvn -f dw-lineage/api/pom.xml spring-boot:run
npm run dev:standard -w sql-tools

# —— 独立 standalone（不管登录，端口 8080 / 5173）
# 注意两边都要指定：后端不设模式变量时默认是 standard。
# 两个变量都认，LINEAGE_RUN_MODE 更具体、优先；跟着 org / model 统一设 DW_AI_MODE 也可以。
LINEAGE_RUN_MODE=standalone mvn -f dw-lineage/api/pom.xml spring-boot:run
npm run dev:standalone -w sql-tools
```

解析与列级血缘不在本仓库内，通过 Maven 依赖引用：

- https://gitee.com/songbaibuxiu/superior-sql-parser.git
- https://gitee.com/songbaibuxiu/sqlflow.git

---

## 快速开始

### 环境要求

- JDK 21
- Maven 3.8+
- Node 22.13+ 与 npm（仅前端需要；依赖在**仓库根**统一安装，ui 已并入根 npm workspaces，见 [ADR-0014](../docs/tech/adr/0014-frontend-package-manager-unification.md)）

### 构建与启动

```bash
# 1. 后端，默认用内嵌 H2，无需任何外部数据库
#    空库启动时 Flyway 自动建表 + 灌初始数据（与 dw-org / dw-model 一致）
(cd api && mvn package -DskipTests && java -jar target/sql-tools-1.0-SNAPSHOT.jar)

# 2. 前端（依赖在仓库根统一安装 —— ui 已并入根 npm workspaces，见 docs/tech/adr/0014）
(cd .. && npm install && npm run dev -w sql-tools)
```
启动后：

| 地址 | 说明 |
|---|---|
| <http://localhost:5173> | 前端（vite dev server，`/api` 已代理到后端） |
| <http://localhost:8080/swagger-ui.html> | 接口文档 |
| <http://localhost:8080/actuator/health> | 健康检查 |

### 校验（`ci.sh`）

本地或流水线用，**校验应用能否通过测试**，不打安装包（打包装用 `./package.sh`）。

```bash
./ci.sh              # 全部：后端测试 + 前端构建
./ci.sh backend      # 只跑 sql-tools 的 mvn test
./ci.sh frontend     # 只构建前端，并检查产物有没有写死后端地址
```

后端会检查实际执行的测试数（默认不少于 100），防止套件被静默跳过却显示成功。
前端会检查 `ui/dist` 里没有硬编码的主机/端口。任一项失败则退出码非 0。

### 安装包部署

打出可分发的安装包（bin / conf / sql / lib / web / logs）：

```bash
./package.sh
# 产物：release/dw-lineage-1.0.0.tar.gz
```

目标机器上只需 JDK 21：

```bash
tar zxf dw-lineage-1.0.0.tar.gz && cd dw-lineage-1.0.0
bin/start.sh        # 默认内嵌 H2，空库启动时 Flyway 自动建表，零配置
```

**表结构由 Flyway 托管**（与 dw-org / dw-model 一致）：空库启动自动建表灌初始数据；
已用 `bin/init-db.sh` 或手工脚本建好的库，Flyway `baseline` 接管，不会重复建表。
之后升级优先让服务启动自动跑新迁移，离线 / 停机窗口可按 `sql/upgrade/` 手工执行。
详见安装包里的 `sql/README.md`。

换数据库只需改 `conf/application.yml` 的 `database.type`（`h2` / `mysql` / `postgresql`）
与连接信息，JDBC URL、驱动、建表脚本会自动匹配。
详见安装包内的 `README.md`。

### 容器化启动

```bash
# 需先按上面第 1、2 步构建出后端 jar
docker compose up -d
```

前端 <http://localhost/lineage/>，后端 8080。nginx 已把 `/api` 反代到后端，因此前端产物不含任何硬编码地址。

---

## 试一下

### 灌一份演示数据（推荐）

新装起来的库是空的，页面上大部分功能没东西可点。跑一次种子脚本即可得到一套
四层数仓（ods → dwd → dws → ads）：9 张带中文名和分区列的表、6 段 SQL 解析出的血缘、
以及一张有两个版本的目标表。

```bash
bin/seed-demo.sh                      # 灌进默认租户/项目
SEED_SECOND_TENANT=1 bin/seed-demo.sh # 顺带建第二个项目与演示租户，用来看隔离效果
```

脚本先读 `/api/auth/config` 判运行模式，再决定要不要登录 —— 三种模式下的行为不一样：

| 运行模式 | 鉴权 | `SEED_SECOND_TENANT=1` |
|---|---|---|
| `standalone` | 无，`/api/**` 全放行 | 第二个项目与演示租户都建出来 |
| `standard` | 用 `SEED_USERNAME` / `SEED_PASSWORD`（默认 `admin` / `123456`）自动调 `/api/auth/login` 换令牌；也可直接给 `AUTH_TOKEN=xxx` 跳过登录 | 只建第二个项目。建租户会被服务端 403（`assertTenantWritable`），脚本按设计跳过并打印原因 |
| `multi` | 必须给 `AUTH_TOKEN`（组织签发的令牌） | 两半都不建：租户与项目由组织平台同步，脚本只往里头灌元数据与血缘 |

`multi` 下先把 `TENANT_ID` / `PROJECT_ID` **填组织侧的编码**（如 `TENANT_ID=tenant02 PROJECT_ID=pj02`）
再跑本脚本。服务端对能 `parseLong` 的值按本地 id 解析、否则按编码查库，所以编码直接填进这两个变量
就能寻址 —— 变量名叫 ID，但值可以是编码。默认值 `1` 在 multi 下指的是本地内置的「默认租户」，
与组织侧的租户没有必然关系，别拿它当默认。

模式探测不出来时（对端不返回 `runMode`）脚本会打印提示，并按最保守的方式处理 —— 不建租户、
不建项目，因为认错方向的代价是往生产库写演示数据。也可以 `RUN_MODE=standalone|standard|multi`
显式指定，省掉那次探测请求。

脚本走 REST 接口而不是直接灌 SQL —— 血缘是一张图，边引用的是列的自增 id，
手写 INSERT 既要自己维护这些 id，又要在三种方言里各写一份，稍有不一致就会造出
一张渲染不出来的图。走接口则由解析器自己生成，且三种后端通吃，附带还是一次端到端冒烟测试。

跑完可以直接看：

- 表基础信息 `/catalog/tables?schema=dwd&table=dwd_order_detail`
- 血缘关系 `/lineage/graph?start=ads.ads_sale_overview`
- 全局搜索 `/search`，搜「金额」或「类目」

### 直接调接口

```bash
curl -X POST http://localhost:8080/api/lineage/analyze \
  -H 'Content-Type: application/json' \
  -d '{
    "dbType": "hive",
    "isCreateTable": true,
    "querySql": "create table ods.users(id int, name string);\ncreate table dws.stat(user_id int, user_name string);\ninsert into dws.stat select id, name from ods.users;"
  }'
```

带上 `-H 'X-Tenant-Id: 2' -H 'X-Project-Id: 3'` 可以指定租户；不带则落到默认租户。

---

## 核心概念

### 元数据从哪来

字段级血缘**需要知道表结构**，否则 `select *` 和不带表前缀的列名无法展开。
元数据来源抽象为 `MetadataProvider`，按优先级串联，前一个查不到才问后一个：

```
SQL 内的 CREATE TABLE  →  外部元数据服务（Gravitino）  →  从 SQL 结构推断
```

全都拿不到的表会出现在响应的 `unresolvedTables` 中，而不是让整个请求失败。

### 方言能力差异（重要）

支持 13 种方言，但**能力不一致**，实测结论：

| 能力 | 结论 |
|---|---|
| 列级血缘分析 | 13 种方言在拿到表结构后**全部可用** |
| 从 SQL 内 DDL 提取表结构 | **trino / presto / sqlserver 不支持** |

那三种方言的 `CREATE TABLE` 会被解析成 `DefaultStatement` 拿不到列，
因此**必须配置外部元数据服务**。调用 `GET /api/dialects` 可查询每种方言的能力位。

### 数据目录与临时表

表全名恒为三段 **`数据目录.库.表`**，数据目录参与唯一性 ——
`hive_prod.ods.orders` 与 `hive_test.ods.orders` 是两张不同的表。
每个项目有且只有一个**默认数据目录**，建表语句里没写目录时就落到它，
因此不存在「没有数据目录」的表。目录在「数据目录」配置页里维护。

SQL 里写两段名（`from ods.orders`）是常态，解析阶段会按默认目录补成三段再入库，
`catalog_name` 有 `NOT NULL` 兜底。两边名字对齐后，血缘表才能关联到元数据、
把中文名和表类型带出来。

页面上则相反：属于**默认目录**的表只显示 `ods.orders`，把那一段藏起来 ——
绝大多数表都在默认目录下，每个名字都顶着同样的前缀纯属噪音。
非默认目录仍显示完整的 `hive_prod.ods.orders`，那才是需要区分的场合。

同一个页面配**临时库/表规则**（库名 / 表名 × 通配符 / 正则）。
ETL 的 SQL 会建一堆中间临时表，命中规则的表在血缘图上被**穿透**掉：

```sql
create table tmp.bb as select * from source_a;
insert into sink_b select * from tmp.bb;
```

| | 结果 |
|---|---|
| 包含临时表 | `source_a → tmp.bb → sink_b` |
| 不含临时表 | `source_a → sink_b`（不是断成两段） |

血缘分析页的开关只影响**看**，**保存一律过滤** —— 库里永远不会存临时表。

> 匹配方式要显式选：`tmp_*` 在通配符下匹配 `tmp_abc`，
> 在正则下 `*` 修饰的是前一个字符 `_`，反而**匹配不到** `tmp_abc`。
> 写错不报错、只是默默匹配不上，所以配置页提供了当场试算的入口。

### 部分失败容忍

多语句脚本中单条语句失败**不影响其余语句**，失败原因通过 `failedStatements` 返回，
不会出现「接口成功但图是空的、用户不知道为什么」的情况。

### 层级语义

最终产出字段为 `level 0`，每向上游一跳 +1，取**最长路径**。
存在环时会被检测出来，环上的边标记 `cyclic`，并在 `warnings` 中给出提示。

---

## 文档

| 文档 | 内容 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构、数据流、关键设计决策 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 开发指南、构建顺序、测试、常见问题 |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | 部署、配置项、数据库切换 |
| [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md) | 已知缺陷与限制，每条附最小复现 SQL |
| [api/README.md](api/README.md) | 后端 API 详细说明 |
| `/swagger-ui.html` | 由代码自动生成的接口文档 |

### 演进记录

| 文档 | 内容 |
|---|---|
| [OPTIMIZATION_PLAN.md](OPTIMIZATION_PLAN.md) | 最初的整体优化方案 |
| [PHASE1_REPORT.md](PHASE1_REPORT.md) | 一期（止血）完成报告 |
| [PHASE2_PLAN.md](PHASE2_PLAN.md) / [PHASE2_REPORT.md](PHASE2_REPORT.md) | 二期（架构重构）计划与报告 |
| [PHASE3_PLAN.md](PHASE3_PLAN.md) / [PHASE3_PROGRESS.md](PHASE3_PROGRESS.md) | 三期（能力升级）计划与进度 |

---

## 当前状态

`sql-tools` 262 通过（H2；另有 6 个 MySQL / PG 集成用例需 `-Dit.*` 开启，含升级脚本对存量数据的改写）。

三期进行中。已完成：血缘持久化的数据模型与多数据库支持（H2 / MySQL / PostgreSQL
三后端共用同一份用例验证）、版本管理、元数据服务接入（Gravitino / dbx）、
自维护元数据目录、七个前端页面，以及租户 / 项目的完整管理面。
数据目录（含默认目录）与临时库表规则的配置页也已就绪。

## 已知限制

完整清单见 [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md)，其中影响最大的一条：

- **函数调用里的限定字段会丢失上游，join + 函数还可能归属到错误的源表**。
  `sum(amount)` 正常，`sum(d.amount)` 拿不到上游；join 场景下
  `count(order_id)` 可能被算到另一张根本没有该列的表上。暂未修复 ——
  写 SQL 时请遵守文档里给出的两条可靠写法

其余：

- **trino / presto / sqlserver** 无法从 `CREATE TABLE` 提取表结构，需配置外部元数据服务
- **ClickHouse** 的关键字列表在上游未实现，编辑器补全对 ck 不可用
- **多租户不是安全边界**：没有登录体系，租户仅作数据隔离维度
- 前端 AntV G6 仍为 v4（已停止特性更新），v5 迁移待评估

## License

Apache License 2.0
