# 开发指南

## 环境

- **JDK 21**
- Maven 3.8+
- Node 22.13+（下限来自 pnpm 时期，见 `ui/package.json` 的 `engines`；迁 npm 后保留未动）
- **npm**（随 Node 附带）—— 前端已并入仓库根的 npm workspaces，依赖在**仓库根**装一次，
  五个工作区共用；不再需要 `corepack` / `packageManager` 字段
  （迁移决策见 [ADR-0014](../../docs/tech/adr/0014-frontend-package-manager-unification.md)）
- Docker（跑 MySQL / PostgreSQL 的持久化测试时需要）

## 测试

```bash
# 后端全量（默认跳过依赖外部服务的用例）
(cd api && mvn test)

# 含需要 Gravitino 服务的用例
(cd api && mvn test -Pexternal-tests)

# 只跑某个套件
(cd api && mvn test -Dtest=SQLLineageMergerCorrectnessTest)
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
(cd api && mvn test -Dtest='H2LineageRepositoryTest,MySqlLineageRepositoryIT,PostgresLineageRepositoryIT' \
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
cd ..                 # 依赖装在仓库根，不在 ui 子目录里单独装
npm install
npm run dev -w sql-tools              # http://localhost:5173，/api 已代理到 127.0.0.1:18082
npm run build -w sql-tools            # 带类型检查
npm run build:no-check -w sql-tools   # 跳过类型检查，CI 与打包用
```

> `-w` 后面是**包名**（`sql-tools`），不是目录名 —— 目录叫 `ui`。

后端地址通过环境变量配置，**不要硬编码**：

| 变量 | 说明 |
|---|---|
| `VITE_DEV_PROXY_TARGET` | 开发时 vite 代理的目标，默认 `http://127.0.0.1:18082`；`VITE_API_BASE_URL` 填绝对地址时以后者为准 |
| `VITE_API_BASE_URL` | **我要调的后端**；留空表示走相对路径由 nginx 反代 |
| `VITE_BASE_URL` | **我自己的对外地址**；容器启动时渲染进 `config.json` |
| `VITE_API_TIMEOUT` | 请求超时（毫秒），默认 60000 |

打包态改后端地址**不必重新构建**：容器启动时由 `docker-entrypoint.d/25-app-config.sh`
渲染 `config.json`，前端挂载前读它（见 `src/config/appConfig.ts`）。


---

## 添加一种新方言

1. 在 `api/pom.xml` 加对应的 `superior-xxx-parser` 依赖
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

**（历史，pnpm 时期）前端 `ERR_PNPM_IGNORED_BUILDS: Ignored build scripts: core-js, esbuild`**
pnpm 10 起默认不执行依赖的 install 脚本，靠 `ui/pnpm-workspace.yaml` 的 `allowBuilds` 放行。
2026-09 迁到 npm 后这个问题不再存在 —— **npm 默认就执行依赖的 install 脚本**，没有等价配置项，
`pnpm-workspace.yaml` 也已删除。保留此条只为解释历史提交；若在别处再见到 `allowBuilds`，
说明那是还没迁完的分支。

**前端构建报 `Rollup failed to resolve import "@antv/algorithm/lib/asyncIndex"`**
`@antv/g6-pc@0.8.18` 引用了一个在 `@antv/algorithm` 任何版本中都不存在的文件（上游缺陷）。
已用 `src/shims/antv-algorithm-async.ts` + vite alias 顶替，上游修复后可删除。

**前端构建报 `__spreadArray is not exported by tslib`**
依赖图里混入了 tslib 1.x，而 `@antv/algorithm` 需要 2.x 的辅助函数。
已在**仓库根** `package.json` 的 `overrides` 中统一到 `^2.6.0` —— 必须是根，
npm 只在 workspaces 根应用 `overrides`，放子包会被静默忽略。

**前端构建报某个包 `Failed to resolve`，但该包确实装了**
多半是**隐式依赖**：源码直接 import 了某个传递依赖，却没写进 `package.json`。
以前靠 `shamefully-hoist`（pnpm）或「向上遍历到仓库根 `node_modules`」（npm）侥幸能用，
一旦安装结构变了就暴露。正确做法是把它声明为直接依赖（`@antv/layout` 就是这么修的）。
迁到 npm workspaces 时正是靠这条抓出 `@dw-ai/engine` —— 数据地图一直在用它，
`package.json` 里却从来没写过，全靠向上遍历到根那条软链。

---

## 代码约定

- 注释写**为什么**，不写代码已经表达的**是什么**
- 新增持久化方法，第一个参数必须是 `LineageContext`（租户隔离的强制约束）
- 向其它线程提交任务时，用 `TenantContextHolder.wrap()` 透传租户上下文
- 修 bug 时先写一个能复现的失败用例，再改代码
