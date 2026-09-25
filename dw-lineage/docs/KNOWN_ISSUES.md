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

`TenantInterceptor` 只解析 `X-Tenant-Id` / `X-Project-Id` 请求头，
**不校验该租户是否存在**。它放行到什么程度，取决于运行模式：

- **standalone**（无账号体系）：任何能访问服务的人都可以切换租户，也可以创建租户。
  手工 `curl -H "X-Tenant-Id: 999"` 能写出一批在 `tenant` 表里没有对应记录的孤儿数据。
- **standard**（本地账号）：租户头被**整条忽略**，请求恒定落在默认租户（id=1）上；
  租户写接口对所有人 403。上一项里的孤儿数据写不出来 —— 租户维度已从 standard 整体移除。
  项目维度仍然开放（要管理员）。
- **multi**：租户头必须带，且必须能解析成已知租户，否则 400。但它挡的是「编码写错」，
  不是「故意换一个租户」—— 令牌里没有租户声明，见 ADR-0011。

这是明知的取舍（定位是内部工具）。数据面的隔离是完整的
（每张业务表都带租户列、仓储方法首参强制 `LineageContext`、`TenantIsolationArchTest`
在源码层面扫描每条原生 SQL、`TenantIsolationApiTest` 从 HTTP 层验证三个域两两不串），
缺的是身份来源。standalone 接入登录体系时一并解决。

> **这条不是理论风险。** 写 `bin/seed-demo.sh` 时，一个 `sed` 贪婪匹配的 bug 让脚本把
> 租户 id 取成了嵌套的项目 id，于是整套演示数据被写进了并不存在的「租户 3」——
> 全程没有任何报错，页面上也看不出异常，直到比对租户列表才发现。
> 脚本现在在每次切换域之后都调一次 `check_service`（它会查 `/api/context` 的
> `tenantExists`）来兜住这类错误；但服务端本身仍然照单全收（standalone 下如此 ——
> standard 已忽略租户头，这类事故写不进去）。
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

---

## 9. 组织删项目后，本模块的镜像行不会消失（pull 改造的已知代价）

项目同步由组织「推送」改成模块「按需拉取」之后，组织**不再知道模块的地址**，
所以「新建项目」靠下次访问懒拉补上，「改项目」也能被后台对账自动修正（实测 ~60s，
见 `docs/tech/06-0.1.5-routes.md` §7），但**「删项目」没有任何事件能到达模块**：
拉取只会往本地写，不会往回删。

组织删掉项目后，本模块的表现（2026-09-24 真机实测，lineage 18092 / model 18091）：

| 模块 | 表现 | 为什么 |
|---|---|---|
| **lineage（本模块）** | **200**，页面照常打开，显示的是**已删项目**的镜像行（名字还是删除前的） | 本地那一行还在，租户/项目都解析得出来；本模块没有许可表、**也不调 `authz/check`**，所以没有任何一个环节会去核实「组织还认不认这个项目」 |
| dw-model | 403 `{"error":"项目编码未同步"}` | 它的 multi 路径每个项目级请求都调组织 `authz/check`，组织答「没有」→ 翻成 403 |

也就是说 **原计划里预期的那条 403 只在 dw-model 成立**；本模块的表现是「继续服务一个
已经不存在的项目」，比 403 更宽松，值得单独记一笔。

日志上是能看出来的：组织删掉后，下一次后台对账会打出一行
`组织侧没有这个项目, tenantCode=..., projectCode=...`，随后落 15 秒负缓存
（不再反复问），但**本地镜像行不会被删** —— 用户视角就是这个项目一直还在。

**不越权**：本模块的判定本来就不依赖组织（见 §6「多租户不是安全边界」），
所以这不是「删掉的项目被重新授权」，而是「本模块从来就没有撤销这一说」。
真正的授权在组织侧与 `authz/check`（dw-model 那条路），本模块的租户/项目维度是**数据组织维度**。

**下一版可选的自愈**：在拿到「组织侧没有这个项目」这个信号时（拉取已经拿到了它，
只是现在只用来写负缓存）顺手删本地镜像行。没做是因为那要在一处后台线程里删有外键关联
的行（血缘表按 project_id 关联），删错或事务半途失败留下的坏状态比留一行孤儿更贵。
真要做，得先想清楚「孤儿项目的数据是留着还是连带清理」—— 那是产品决定，不是实现细节。

---

## 10. 被门户 iframe 嵌入：三项已确认的取舍

本模块的页面现在会被组织门户（dw-org）用 iframe 嵌进壳里用，三件事是**刻意这样**的，
不是漏配 —— 改之前先读完：

1. **iframe 没有 `sandbox` 属性**（= 全权限：可跳转顶层、可开弹窗、可发任意 postMessage）。
   加上去会让一批前端行为静默失效（弹窗、下载、`sessionStorage` 共享），而本模块是重前端，
   风险面比收益大。本期的决定是**先不加**，与宿主同源信任（同一套令牌体系）。
   真要去掉这份信任，正确做法是给 `sandbox` 白名单逐条验证，而不是顺手加上。
2. **`X-Frame-Options: DENY` 换成了 CSP `frame-ancestors`**，取值来自 `cors.allowed-origins`
   （见 `SecurityConfig.frameAncestors`）。两套语法不同：`http://localhost:*` 这类**端口通配
   在 CSP 里是非法指令**，浏览器会整条忽略（表现是「配了白名单结果谁都能嵌」，比不配更糟），
   所以通配条目会被剔除并告警；白名单为空或全是通配时**保持 DENY** —— 宁可嵌不进去，
   也不能在配置不完整时静默允许任何人嵌。
3. **子端白名单的正确来源是 `hostOrigin`（宿主通过 `#boot=` 自报），`document.referrer` 只是兜底。**
   宿主没自报 origin、后端也没配 `org-ui-url` 时**不会报错**，表现是「iframe 里频繁 401」——
   排查时先看这两项，别去查令牌本身（`docs/tech/06-0.1.5-routes.md` §7 记了这条）。
   另外宿主页面**不能**给 iframe 加 `referrerpolicy="no-referrer"`：那会掐掉 referrer 兜底。
   （2026-09-25 起不再有 `VITE_ORG_ORIGIN` —— 白名单的另外两项是运行期推导出来的，
   见 `ui/src/config/runtime.ts` 的 `orgOrigin`。）
