# 开发指南

## 环境

- **JDK 21**
- Maven 3.8+
- Node 22.13+（pnpm 11 依赖 Node 内置 `node:sqlite`）
- **pnpm 11** —— 版本由 `sql-tools-vue/package.json` 的 `packageManager` 字段声明，
  执行 `corepack enable` 后会自动使用正确版本。**不要手工安装其它版本**：
  pnpm 10/11 之间配置项的位置和名称有变动，版本不一致会导致构建失败
- Docker（跑 MySQL / PostgreSQL 的持久化测试时需要）

## 测试

```bash
# 后端全量（默认跳过依赖外部服务的用例）
(cd sql-tools && mvn test)

# 含需要 Gravitino 服务的用例
(cd sql-tools && mvn test -Pexternal-tests)

# 只跑某个套件
(cd sql-tools && mvn test -Dtest=SQLLineageMergerCorrectnessTest)
```

### 持久化的多数据库测试

H2 无需任何准备，直接跑。MySQL / PostgreSQL 需要真实数据库，
未提供连接信息时用例会自动跳过（不会失败）：

```bash
# 起临时数据库
docker run -d --name sqltools-mysql-test \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=dw_lineage \
  -p 13306:3306 mysql:8
docker run -d --name sqltools-pg-test \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=dw_lineage \
  -p 15432:5432 postgres:15

# 三后端共用同一份用例
(cd sql-tools && mvn test -Dtest='H2LineageRepositoryTest,MySqlLineageRepositoryIT,PostgresLineageRepositoryIT' \
  -Dit.mysql.url='jdbc:mysql://localhost:13306/dw_lineage?allowPublicKeyRetrieval=true&useSSL=false' \
  -Dit.mysql.username=root -Dit.mysql.password=root \
  -Dit.postgres.url=jdbc:postgresql://localhost:15432/dw_lineage \
  -Dit.postgres.username=postgres -Dit.postgres.password=postgres)

# 用完清理
docker rm -f sqltools-mysql-test sqltools-pg-test
```

### 关键测试套件

| 套件 | 覆盖 |
|---|---|
| `util/SQLLineageMergerCorrectnessTest` | 合并算法：层级、同层序号、环、输出确定性、深链路 |
| `lineage/LineageEndToEndTest` | 整条链路端到端，含部分失败容忍 |
| `dialect/DialectCapabilityTest` | 13 种方言能力矩阵，锁定能力位与实测一致 |
| `metadata/MetadataProviderTest` | 元数据来源的优先级串联与降级 |
| `lineage/SqlParseExecutorTest` | 解析超时、栈溢出隔离 |
| `persistence/AbstractLineageRepositoryTest` | 持久化契约，三种数据库共用 |
| `persistence/MigrationDialectTest` | 迁移脚本与递归 CTE 可用性 |

---

## 校验（`ci.sh`）

用法见根目录 [README.md](../README.md)「校验（`ci.sh`）」一节。开发时常用：

```bash
./ci.sh              # 全部
./ci.sh backend      # 只跑 sql-tools 测试
./ci.sh frontend     # 只构建前端 + 守卫检查
```

> 各子目录下的 `.github/workflows/` 是 GitHub Actions 格式。
> 本项目托管在 Gitee，这些 workflow **不会被 Gitee 执行**，
> 请以 `ci.sh` 为准，或按 Gitee Go 的格式改写。

## 前端

```bash
cd sql-tools-vue
corepack enable   # 首次，让 pnpm 版本跟随 packageManager 字段
pnpm install
pnpm dev              # http://localhost:5173，/api 已代理到 localhost:8080
pnpm build            # 带类型检查
pnpm build:no-check   # 跳过类型检查，CI 与打包用
```

后端地址通过环境变量配置，**不要硬编码**：

| 变量 | 说明 |
|---|---|
| `VITE_DEV_PROXY_TARGET` | 开发时 vite 代理的目标，默认 `http://localhost:8080` |
| `VITE_API_BASE_URL` | 生产环境；留空表示走相对路径由 nginx 反代 |
| `VITE_API_TIMEOUT` | 请求超时（毫秒），默认 60000 |

---

## 添加一种新方言

1. 在 `sql-tools/pom.xml` 加对应的 `superior-xxx-parser` 依赖
2. 在 `DatabaseTypeEnum` 加一行：

```java
NEWDB("newdb", NewDbHelper::parseMultiStatement, true,
        NewDbHelper::sqlKeywords, NewDbHelper::checkSqlSyntax),
```

第三个参数是 `ddlMetadataSupported` —— **必须用实测结果填写**，不要猜。
3. 在 `DialectCapabilityTest.CREATE_TABLE_SQL` 加该方言的建表用例，跑测试验证能力位

`DialectCapabilityTest` 会断言能力位与实测一致，填错会直接失败。

---

## 添加一种新的元数据来源

实现 `MetadataProvider` 即可，不需要改解析主流程：

```java
public class MyMetadataProvider implements MetadataProvider {
    public String name() { return "MySource"; }

    public MetadataResolution resolve(Set<QualifiedObjectName> tables) {
        // 批量解析；查不到的放进 unresolved，不要抛异常
    }
}
```

然后在 `MetadataServiceFactory` 里加入 `LineageAnalysisPipeline.chain(...)` 的优先级链。

---

## 常见问题

**测试显示 `Tests run: 0` 但构建成功**
测试被静默跳过了。历史上 `sql-tools` 因引入 JUnit 5 后缺 `junit-vintage-engine`
出现过。CI 里已加「测试数下限」守卫拦截这种情况。

**血缘结果是空的**
先看响应里的 `warnings`、`failedStatements`、`unresolvedTables`，
它们会说明是「没有可解析的写入语句」「某条语句解析失败」还是「表结构未知」。

**trino / presto / sqlserver 解析出的血缘缺列**
这三种方言无法从 `CREATE TABLE` 提取表结构，必须配置外部元数据服务。
见 `GET /api/dialects` 的能力位。

**H2 文件锁冲突**
默认连接串带了 `AUTO_SERVER=TRUE`。若仍冲突，确认没有多个进程同时打开同一个库文件。

**前端 `ERR_PNPM_IGNORED_BUILDS: Ignored build scripts: core-js, esbuild`**
pnpm 10 起默认不执行依赖的 install 脚本。配置在 `sql-tools-vue/pnpm-workspace.yaml`
的 `allowBuilds` 中（pnpm 11 已把该设置从 package.json 的 `pnpm` 字段挪到这里并改名）。
若报错说明该文件缺失或未被拷贝 —— 例如 Docker 构建时忘了 COPY 它。

**前端构建报 `Rollup failed to resolve import "@antv/algorithm/lib/asyncIndex"`**
`@antv/g6-pc@0.8.18` 引用了一个在 `@antv/algorithm` 任何版本中都不存在的文件（上游缺陷）。
已用 `src/shims/antv-algorithm-async.ts` + vite alias 顶替，上游修复后可删除。

**前端构建报 `__spreadArray is not exported by tslib`**
依赖图里混入了 tslib 1.x，而 `@antv/algorithm` 需要 2.x 的辅助函数。
已在 `pnpm-workspace.yaml` 的 `overrides` 中统一到 `^2.6.0`。

**前端构建报某个包 `Failed to resolve`，但该包确实装了**
多半是**隐式依赖**：源码直接 import 了某个传递依赖，却没写进 `package.json`。
以前靠 `shamefully-hoist` 侥幸能用，pnpm 新版本下不再 hoist 就会暴露。
正确做法是把它声明为直接依赖（`@antv/layout` 就是这么修的）。

---

## 代码约定

- 注释写**为什么**，不写代码已经表达的**是什么**
- 新增持久化方法，第一个参数必须是 `LineageContext`（租户隔离的强制约束）
- 向其它线程提交任务时，用 `TenantContextHolder.wrap()` 透传租户上下文
- 修 bug 时先写一个能复现的失败用例，再改代码
