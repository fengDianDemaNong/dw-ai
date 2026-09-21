# 已知问题

记录实测确认过的缺陷与限制。每条都给出可复现的最小 SQL 与实测输出，方便验证是否已修复。

最后核实：2026-08-23，H2 后端，`hive` 方言（第 2 条为 1.0.3 修复记录）。

---

## 1. 列级血缘：函数调用里的限定字段会丢失或归属错误 ★ 影响最大

**现象分三种，严重程度递增。**

复现前提：`dwd.dwd_order_detail` 与 `ods.ods_category` 的表结构已在元数据目录里。

### 1.1 别名 + 函数 → 上游整条丢失

```sql
-- ✅ 正常：s <- dwd_order_detail.amount
select user_id, sum(amount) as s from dwd.dwd_order_detail group by user_id

-- ❌ 丢失：s 没有任何上游
select d.user_id, sum(d.amount) as s from dwd.dwd_order_detail d group by d.user_id
```

别名单独用没问题（`select d.city` 正常），函数单独用也没问题（`upper(city)` 正常），
两者同时出现才断。`upper(d.city)`、`max(d.name)` 同样中招。

### 1.2 全表名限定 + 多表 join → 非首表的字段丢失

```sql
-- ❌ category_name 没有上游（ods_category 是 join 进来的第二张表）
select dwd.dwd_order_detail.category_id, ods.ods_category.category_name
from dwd.dwd_order_detail
join ods.ods_category on dwd.dwd_order_detail.category_id = ods.ods_category.category_id
```

### 1.3 join + 函数 + 不限定字段 → 归属到<b>错误的源表</b>

这一种最危险：不是查不到，是查出来是错的，页面上看不出异常。

```sql
select d.category_id,
       max(category_name) as category_name,
       count(order_id)    as order_cnt,
       sum(amount)        as total_amount
from dwd.dwd_order_detail d
join ods.ods_category c on d.category_id = c.category_id
group by d.category_id
```

实测输出：

```
category_id    <- dwd_order_detail.category_id   ✅
category_name  <- ods_category.category_name     ✅
order_cnt      <- ods_category.order_id          ❌ ods_category 里根本没有 order_id
total_amount   <- ods_category.amount            ❌ ods_category 里根本没有 amount
```

### 1.4 算术表达式与 CASE WHEN → 上游丢失（与别名无关）

```sql
select user_id, amount * 2 as amt2 from dwd.dwd_order_detail                    -- ❌ amt2 无上游
select user_id, case when amount > 0 then city else null end as x from ...      -- ❌ x 无上游
select user_id, upper(city) as c2 from dwd.dwd_order_detail                     -- ✅ 正常
```

### 目前可靠的写法

在修复之前，下面两条组合起来是实测能保证列级归属正确的：

- **多表 join**：只用「别名 + 直传字段」，join 语句里不出现函数
- **聚合**：只从<b>单表</b>取数，函数参数不加任何限定前缀

`bin/seed-demo.sh` 就是按这两条写的，因此它造出来的血缘图 41 条边引用全部归属正确。

缺陷在上游列级分析引擎，不在本应用代码里。直传字段走「字段到字段」映射，
函数参数走表达式路径，两条路径对限定名的处理不一致。

---

## 2. CTAS 的多语句脚本（1.0.3 已修，记下来免得再踩）

```sql
create table tmp.bb as select * from a;
insert into sink_b select * from tmp.bb;
```

列级分析在这里两头堵：预先把 `tmp.bb` 的结构喂进元数据服务，分析 CTAS 时会报
`Destination table 'tmp.bb' already exists` 并把**整条语句丢掉**；不喂，
后面那条 `insert` 又报 `table tmp.bb metadata not exists`。而链路末端的
`InferredMetadataProvider` 是无条件兜底的，会照着 select 列表给 `tmp.bb`
推断出一份结构 —— 所以默认落在前一种情况，**CTAS 的血缘一条也拿不到**。

修法在 `LineageAnalysisPipeline`：把 CTAS 的目标表从「待解析表」里排除，
并改成**逐条分析、每分析完一条 CTAS 就把它现建的表登记进元数据服务**。
上游引擎本身没动。

> 这个缺陷正好卡在临时表过滤的关键路径上：`a → tmp.bb` 那一段丢了，
> 过滤时想穿透 `tmp.bb` 也无从接起，图会直接变空。

---

## 3. 转换表达式没有落库（P6，尚未开始）

即使血缘连接是对的，`dws.t.amt = sum(a.price * b.rate)` 里的表达式原文也没有保存 ——
`lineage_edge.transform` 字段建好了但一直是空的。这是列级血缘相对表级血缘的核心差异化价值。
详见 `PHASE3_PLAN.md` 第 8 节。

---

## 4. 上游解析器的方言缺口

来自第二期的核实结论，非本项目可控：

- `trino` / `presto` / `sqlserver` 的 `CREATE TABLE` 无法提取表结构，
  这几种方言必须依赖外部元数据服务（`GET /api/dialects` 会返回该能力位）
- ClickHouse 的 `sqlKeywords()` 未实现，编辑器补全没有关键字

---

## 5. dbx 元数据服务

- **拿不到分区列**：实测 `/api/schema/columns` 的响应里没有分区标记。
  Hive / Spark 的分区列对血缘是必需的，因此分区表场景建议用 Gravitino 交叉验证
- **`/api/connection/list` 明文返回数据库密码**。已做三重防护（不记日志、解析后立即丢弃密码字段、
  对外响应过滤），并有测试钉死；接入时若换了 dbx 版本需重新确认
- 使用前必须在 dbx 界面里<b>保存</b>一个连接（未保存的连接只存在于建立它的那个会话里，
  跨进程用不了）。**不再需要手写 `extraConfig.connectionId`** ——
  导入弹窗里的「连接」下拉会列出 dbx 中已保存的连接，选一个即可
- **MySQL 这类数据库没有 schema 层**：dbx 的 `/api/schema/schemas` 对它们返回空数组，
  而 `/api/schema/tables` 要求把库名当 schema 传。级联里已做退化处理（schema 下拉显示库名本身），
  否则三级级联会断在这一步

---

## 6. 多租户不是安全边界

当前没有登录体系，`TenantInterceptor` 只解析 `X-Tenant-Id` / `X-Project-Id` 请求头，
**不校验该租户是否存在**。因此：

- 任何能访问服务的人都可以切换租户，也可以创建租户
- 手工 `curl -H "X-Tenant-Id: 999"` 能写出一批在 `tenant` 表里没有对应记录的孤儿数据

这是明知的取舍（定位是内部工具）。数据面的隔离是完整的
（每张业务表都带租户列、仓储方法首参强制 `LineageContext`、`TenantIsolationArchTest`
在源码层面扫描每条原生 SQL、`TenantIsolationApiTest` 从 HTTP 层验证三个域两两不串），
缺的是身份来源。接入登录体系时一并解决。

> **这条不是理论风险。** 写 `bin/seed-demo.sh` 时，一个 `sed` 贪婪匹配的 bug 让脚本把
> 租户 id 取成了嵌套的项目 id，于是整套演示数据被写进了并不存在的「租户 3」——
> 全程没有任何报错，页面上也看不出异常，直到比对租户列表才发现。
> 脚本现在在每次切换域之后都调一次 `check_service`（它会查 `/api/context` 的
> `tenantExists`）来兜住这类错误；但服务端本身仍然照单全收。
>
> **隔离本身是好的**：这批数据确实只有「租户 3」能看到，别的租户读不到 ——
> 问题是没人管得着它，既不在租户列表里，也没法通过页面删除。

---

## 7. 前端

- `markVersionCurrent` / `deleteLineageVersion` 两个接口有 API 无入口；
  而「表基础信息」页的删除提示写着「如需清理请在血缘关系页删除对应版本」，指向一个不存在的按钮
- `services/api.ts` 里有 11 个定义了但全前端零调用的函数
- 血缘图缺「曲线/直线切换」与「复制清单抽屉」两个原计划的画布能力

---

## 8. 测试

`HiveSqlLineageTest2` 与 `SparkSqlLineageTest1` 共 5 个用例，
类名不匹配 surefire 默认的 include 模式（`Test*` / `*Test` / `*Tests` / `*TestCase`），
**从未被执行过**。改名即可纳入。
