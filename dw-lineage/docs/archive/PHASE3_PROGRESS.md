# 第三期进度

> 更新：2026-08-22

## 已完成

### 前置：dbx API 实测（原计划第 1 周任务）
用 `localhost:4224` + 临时 PostgreSQL 数据源**完整跑通** login → connect → schema 全链路，
拿到了 `/api/schema/{databases,schemas,tables,columns}` 的真实响应结构。
发现 6 处与官方文档不符，已全部写入 `PHASE3_PLAN.md` 5.2 节，实现时以实测为准。
测试用的连接已从 dbx 中清理。

### P1 数据模型
- 7 张表：`tenant` / `project` / `lineage_version` / `lineage_table` / `lineage_column`
  / `lineage_edge` / `metadata_source`
- 全部带 `tenant_id` + `project_id`
- 三套 Flyway 方言脚本：`db/migration/{h2,mysql,postgresql}`

### P2 多数据库
- H2（file 模式，默认）/ MySQL 8+ / PostgreSQL 12+
- `JdbcLineageRepository`：写入用批处理，图遍历用递归 CTE（三方言共用一份 SQL）
- **三种后端共用同一份 14 个用例，全部通过**

### P3 版本管理（持久化层）
新建（版本号项目内递增）、设为当前（项目内互斥）、删除（级联清理血缘数据）、
删除当前版本后自动回退到最新、按 sql_hash 幂等查询。

### 租户隔离
- `TenantContextHolder`，并解决了计划中标记的坑：
  `SqlParseExecutor` 在独立线程执行解析，ThreadLocal 不会自动传递，
  已统一用 `wrap()` 透传上下文
- `LineageRepository` 每个方法第一个参数强制为 `LineageContext`
- **4 个跨租户越权负向用例**：查版本 / 遍历血缘 / 删除 / 按哈希查，均验证拿不到别人的数据

### P4 元数据服务接入（Gravitino + dbx）+ 配置页
- **租户打通**：`V2__seed_default_tenant.sql`（三方言）写入默认 tenant/project；
  `TenantInterceptor` 从 `X-Tenant-Id` / `X-Project-Id` 解析，缺省落默认租户
- **`metadata_source` 接线**：`JdbcMetadataSourceRepository`（每个方法首参强制 `LineageContext`）
  + `MetadataSourceController` 的 CRUD 与**测试连接**
- **凭据加密**：`CredentialCipher`，AES-256-GCM，主密钥取自 `METADATA_SECRET_KEY`。
  未配置时服务照常启动，但保存带凭据的服务会明确报错 —— 宁可失败也不明文落库
- **`DbxMetadataProvider`**：与 `GravitinoMetadataProvider` 并列，按实测契约实现
  （`config` 必带 `id`、`db_type` 只认 `postgres`、databases/schemas 结构不一致、
  连 schema 前必须先 connect、401 只自动重登一次）
- **Gravitino 动态化**：`GravitinoClientRegistry` 按地址缓存客户端，
  取代原先 `@PostConstruct` 建单例、改地址必须重启的写法。yml 的 `gravitino.url` 降级为兜底
- **前端**：引入 vue-router，新增 `/settings/metadata` 配置页
  （列表 / 增删改 / 测试连接 / 适用范围标注），Header 的「元数据来源」下拉改为读取真实配置

## 验证

| 项 | 结果 |
|---|---|
| H2 迁移 + 递归 CTE | 3 用例通过 |
| MySQL 迁移 + 递归 CTE | 2 用例通过（真实 MySQL 8 容器）|
| PostgreSQL 迁移 + 递归 CTE | 2 用例通过（真实 PG 15 容器）|
| 三后端行为一致 | 同一份 14 用例 × 3 = 42 次断言全通过 |
| 凭据加解密 | 6 用例：往返、同明文两次密文不同、篡改被拒、换密钥解不开、未配密钥只在使用时报错 |
| metadata_source 跨租户越权 | 4 个负向用例：列表 / 按 id 读 / 改 / 删，均拿不到别人的数据 |
| dbx 密码不泄漏 | 我方 logger 全级别断言无密码；并用测试钉死 `application.yml` 里 HTTP 报文 logger 的 INFO 级别 |
| DbxMetadataProvider | 7 用例：列解析、分区列告警、查不到进 unresolved、连接未保存时继续尝试、报错列出可用连接 |
| 真机联调 | Gravitino(8090) 与 dbx(4224) 均连通；dbx 经 `host.docker.internal:25432` 读到 postgres 真实表结构 |
| 全量回归 | **135 个测试全绿**（二期末 91，本期 P1~P3 时 108）|

## 过程中修掉的问题

1. **Flyway 10 拆分了数据库模块** —— 只加 `flyway-mysql` 会让 PG 报
   `Unsupported Database: PostgreSQL 15.6`，需补 `flyway-database-postgresql`
2. **H2 的 generated keys 含多列** —— `created_at` 有默认值也被算作 generated key，
   `getKey()` 直接抛「multiple keys」异常，改为按列名取 `id`
3. **递归 CTE 的深度限制漏了一处** —— 递归本身受 `depth` 约束，但最后一次 join
   对所有 walk 节点取边，导致实际多返回一层。已在最终 join 上补同样的约束

## 待办

- [x] 租户拦截器（HTTP 层解析租户）
- [x] 架构测试（扫描原生 SQL 强制带 tenant_id）—— `TenantIsolationArchTest`
- [x] **租户 / 项目 CRUD**（`PHASE3_PLAN.md:416` 第 1 周排期的一条，此前一直缺）：
      `TenantAdminRepository` / `TenantAdminService` / `TenantAdminController`，
      建租户时自动生成默认项目并种内置 CATALOG 元数据源；删除有数据的租户返回 409。
      前端 `stores/tenant.ts` + 顶栏切换器 + `/settings/tenants` 管理页，
      请求头在 `utils/request.ts` 一处注入
- [x] 版本管理 REST 接口
- [x] P4 元数据服务接入（`metadata_source` CRUD、凭据加密、DbxProvider、配置页）
- [ ] **P4 收尾**：dbx 侧需要在其界面上保存一个数据库连接，
      把它的 id 填进该元数据服务的 `extraConfig.connectionId`。
      当前 dbx 中 0 个已保存连接，未保存的连接只存在于建立它的那个会话里，
      跨进程用不了；dbx 也没有提供删除已保存连接的接口，故未代为创建
- [x] P5 全局图查询接口 —— 合并为一个 `GET /api/lineage/graph`（上溯/影响分析由
      `direction` 参数区分），未做原方案 A 里的孤儿检测 `GET /graph/orphans`
- [ ] P6 转换表达式下钻（含可行性验证）
- [ ] P7 可观测性

## 仍然欠着的（2026-08-23 清点）

- **登录 / 鉴权**：当前没有任何认证，租户是数据组织维度而非安全边界。
  因此 `TenantInterceptor` 也不校验租户是否存在 —— 手工 curl 一个不存在的租户号，
  仍能写出一批没有 `tenant` 记录的孤儿数据。这是明知的取舍，接入登录体系时一并解决
- **跨域操作的状态码**：已修 —— `@Repository` 会把仓储抛的 `IllegalArgumentException`
  包成 `InvalidDataAccessApiUsageException`，绕开 400 处理器变成 500
  「服务内部错误，请联系管理员」。`GlobalExceptionHandler` 现在会拆包还原成 400。
  回归用例见 `TenantAdminApiTest.markingAnotherTenantsVersionCurrentReturns400Not500`
- **版本管理 UI**：`markVersionCurrent` / `deleteLineageVersion` 两个接口有 API 无入口，
  而 `catalog-tables.vue` 的删除提示却写着「如需清理请在血缘关系页删除对应版本」，
  指向一个不存在的按钮
- **版本 diff** `GET /versions/diff?from=&to=`（`PHASE3_PLAN.md:250` 接口表里承诺、正文未展开）
- `api.ts` 有 11 个定义了但全前端零调用的函数
- `HiveSqlLineageTest2` / `SparkSqlLineageTest1` 共 5 个用例，
  因类名不匹配 surefire 默认 include 模式，从未被执行过
- 顺延项：批量异步 + 缓存、前端 Pinia 重构、`JdbcMetadataProvider`、Gravitino 元数据缓存
