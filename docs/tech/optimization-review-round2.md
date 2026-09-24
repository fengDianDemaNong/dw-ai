# 代码库优化体检（第二轮）

> 日期：2026-09-22 · 方式：只读审查，**每条结论都有可复现证据**
> 与 [`optimization-plan.md`](optimization-plan.md) 的关系：那份是**方案**（含首次审查与复审），
> [`optimization-execution-report.md`](optimization-execution-report.md) 是**执行记录**。
> 本份是**对着当前工作树实测的第三份**：验证执行结果、找出方案未覆盖与执行后新产生的问题。

## 一句话结论

方案里 P0/P1 的代码改动**确实做完了且质量很高**（见 §6 已验证项），
但**成果几乎全部悬在工作树上没落盘**，而**没有任何 CI 在跑那 414 个测试** ——
这两条叠加，等于「用一个月换来的安全网随时可能归零」。

---

## 优先级总览

| 序 | 项 | 模块 | 代价 | 收益 |
|---|---|---|---|---|
| 1 | 优化成果未提交（含 `dw-common/`、根 `pom.xml`、20+ 测试、10 个 ADR） | 全仓 | 极低 | 🔴 止血 |
| 2 | 零 CI，414 个测试无人自动执行 | 全仓 | 低 | 🔴 止血 |
| 3 | 生产默认值携带开发密钥 + `ALLOW_DEV_LOGIN=true` + `admin123` | 三后端 | 低 | 🔴 安全 |
| 4 | `warehouse-multi` 跳过租户归属校验（未文档化） | dw-model | 低（需确认） | 🟠 安全 |
| 5 | 两个 UI 的 `stores/app.ts` 95% 重复（4302 行 → 共享 95%） | dw-org/ui, dw-model/ui | 中 | 🟠 维护 |
| 6 | Mock 种子被打进生产包并随产物发布 | 两个 UI | 极低 | 🟠 安全+体积 |
| 7 | 两个 UI 的 `vite.config.ts` 无 `build` 段（主 chunk 1.6M / 2.0M） | 两个 UI | 低 | 🟠 体验 |
| 8 | 根 `build` 脚本漏掉 dw-org/ui 与 lineage | 根配置 | 极低 | 🟠 正确性 |
| 9 | 三套互不兼容的请求封装（fetch 手写 ×2 + axios ×1） | 三前端 | 中 | 🟠 维护 |
| 10 | `dw-common` 零测试，而改动同时影响两服务 | dw-common | 低 | 🟠 风险 |
| 11 | 血缘 `services/api.ts` 约 1/3 是死代码 | sql-tools-vue | 低 | ⚪ 清理 |
| 12 | 零 ESLint/Prettier，但 lineage 已声明 lint 脚本（必然失败） | 三前端 | 低 | ⚪ 工程化 |
| 13 | 上帝类：`TenantAdminService` 717 行注入 12 个 Mapper 等 | 后端 | 中 | ⚪ 维护 |
| 14 | 异常处理三服务不统一（12+ handler vs 2 handler） | 后端 | 中 | ⚪ 一致性 |
| 15 | 版本号 7 处不一致 | 全仓 | 低 | ⚪ 一致性 |
| 16 | `docs/product/versions` 占 761 文件 / 14 万行，0.1.0~0.1.4 已归档 | docs | 低 | ⚪ 减负 |

---

## 一、P0 可复现性（先止血，其余项的公共前提）

### 1. 优化成果大面积未落盘

**证据**（`git status --porcelain`）：41 新增 / 64 删除 / 38 修改。其中新增包含：

```
?? dw-common/                     ← 33 个共享类，整个模块不在版本控制里
?? pom.xml                        ← 根聚合 pom（P1-5 的成果）
?? docs/tech/adr/0004 ~ 0013      ← 10 个 ADR
?? dw-org/api/src/test/.../RunModeSmokeTest.java 等 20+ 测试
?? dw-lineage/.../auth/、SecurityConfig.java、V3__local_auth.sql
?? docs/tech/optimization-execution-report.md、multi-tenant-review.md
```

验证：`git ls-files dw-common` → **0**；`git ls-files pom.xml` → **空**（均未跟踪）。

**准确的风险描述**（避免误判）：`HEAD` 自身是**自洽的** ——
`git show HEAD:dw-org/api/pom.xml` 里没有 `dw-common` 引用，被删除的 64 个实体类在 HEAD 里都还在。
所以不是「HEAD 构建不了」，真正的风险是：

- 一次 `git checkout .` / `git clean -fd` 就会**不可恢复地**丢掉整个优化轮次（含 414 个测试）；
- 此刻新增的**任何 CI 都会跑在 HEAD 的旧代码上**，等于白加；
- 新的 clone / 协作者拿不到 `dw-common` 与全部 ADR。

**建议**：本条先做，且拆成 3 个 commit 便于 review ——
① `dw-common/` + 根 `pom.xml` + 两侧删除 + 构建链路改造；② 测试与 ADR；③ 文档更新。

### 2. 完全没有 CI，414 个测试无人执行

**证据**：`.github/` 下只有 `CODEOWNERS`（内容仅覆盖 `/docs/product/`），**无 `workflows/`**；
根目录也没有 `.gitlab-ci.yml` / `Jenkinsfile` / `.circleci`。

而 `dw-lineage/ci.sh` 里已经写好了防「测试被静默跳过仍绿灯」的守卫（`MIN_TESTS_SQLTOOLS=100`），
**却没有任何地方调用它** —— 说明历史上已经吃过「测试没跑但以为是绿的」的亏，守卫却闲置着。

**建议**：加一个最小 workflow，命令全部来自现有脚本，不新增构建方式：

```bash
npm ci
npm run test:api          # = mvn -f pom.xml test → 三个后端 + dw-common 依赖顺序
bash dw-lineage/ci.sh     # 自带测试数下限守卫
npm run build             # 注意：当前只构建一个前端，见第 8 条
```

---

## 二、P0 安全

### 3. 生产默认值携带开发密钥与开发登录

**证据**：

- `dw-org/api/src/main/resources/application.yml:39,43`、`dw-model/api/.../application.yml:41,45`、
  `dw-lineage/sql-tools/src/main/resources/application.yml:69-70` ——
  三份都默认 `JWT_SECRET=dw-ai-dev-secret-...`、`ALLOW_DEV_LOGIN=true`；
- 引导管理员口令默认 `admin123`（org yml:34 / model yml:36）；
- `docker-compose.yml:59-60,104-106` 写成 `${JWT_SECRET:-dev值}` —— **漏配就静默用开发密钥**；
- 发布产物里同样是写死的：`dw-model/release/dw-model-0.1.3/conf/env.sh:30-33`。

**影响**：漏配一个环境变量 = 任何人可伪造 JWT 并以开发登录进系统。

**关键参照（同一仓库里已有正确做法）**：`MODULE_TOKEN` 已实现「留空即 401」的 fail-fast
（`dw-org/api/application.yml:47`）。**密钥类配置应对齐这条**：生产 profile 下不提供默认值，
缺失即启动失败，而不是静默降级。

> 顺带确认：`CORS_ORIGINS` 默认**不是** `*`（仅本地端口），这条不用改。

### 4. `warehouse-multi` 跳过租户归属校验（未文档化，需确认）

**证据**：`dw-model/api/src/main/java/com/dwai/platform/auth/TenantFilter.java:99-124`

```java
boolean warehouseMulti = props.isWarehouseOnly() && props.isMulti();
if (!warehouseMulti && !canAccessTenant(userId, platformAdmin, headerTenant)) { ...403... }
...
tenantRole = ut == null ? (warehouseMulti ? "member" : null) : ut.getTenantRole();
```

即：产品形态为 `warehouse` 且模式为 `multi` 时，**既不校验用户是否属于该租户，也在无成员记录时默认授予 `member`**。
对照 `dw-org` 的同名文件 `TenantFilter.java:100` 没有这个分支。

**为什么需要确认而不是直接判为 bug**：仓库已有 `MultiTenantIsolationTest`（5 个用例：
跨租户读/改被拒、IDOR 被拒、未知 code 返回 400 而非静默落租户 1），
说明越权是有测试守着的；`warehouseMulti` 是个**刻意的命名分支**，
合理猜测是「warehouse 独立进程部署时 `user_tenant` 表不同步，归属判定交给组织侧 `authz/check`」。

**但**：我在 `docs/tech/*.md` 与 `docs/tech/adr/*.md` 全文搜索
`warehouseMulti` / `warehouseOnly` / `warehouse-only` —— **零命中**。
`ADR-0010 多租户止血` 通篇没提这条豁免。

**建议（二选一，都很便宜）**：
- 若是设计意图 → 写进 ADR，明确「授权由组织 `authz/check` 兜底，本地不做成员校验」，
  并补一个测试钉住「组织 deny 时必须 403」；
- 若是遗漏 → 与 `dw-org` 对齐，去掉 `!warehouseMulti` 短路。

### 5.（已排除的误报，记录以免重复调查）

- `dw-lineage/.../conf/SecurityConfig.java:106-107` standalone 下 `anyRequest().permitAll()` ——
  **是有意为之且注释详尽**（standalone 即无登录；`/api/auth/me|profile|password` 特意不在放行列表里，
  注释还写明「少写一条就是匿名能改密码」）。不作为问题。
- `TenantInterceptor` 把 `/api/tenants` 列入不解析租户的路径 —— 同文件 36-40 行注释说明
  「跨租户管理面，作用对象由路径参数指定，本来就不读租户上下文」。不作为问题。

---

## 三、P1 前端

### 5. 两个 UI 的核心 store 是近逐字节重复

**证据**：

```
dw-org/ui/src/stores/app.ts     2173 行
dw-model/ui/src/stores/app.ts   2129 行
归一化（抹掉数字）后 diff：158 行  → 共享度约 95%
```

两者第 37 行都是 `import { createSeed, seedServe } from '../mock/seed';`，导出面同构。
`AppState`（`packages/engine/src/types.ts:474-500`）22 个集合同装在**一个 `reactive`** 里：
租户/IAM（members、licenses）+ 建模（domains、tables、drafts）+ 执行（jobs、clusters）
+ 服务（serveFolders）+ 知识 —— 五类职责混装。

**影响**：任一改动双写，「改了 A 忘了 B」是必然结局（`dw-org`/`dw-model` 后端的重复已经演过一次，
所以才抽了 `dw-common`；前端是同一个剧本）。

**建议**：仓库里**已有成功先例** `packages/engine`（两个 UI 通过 workspace 依赖引用它，
且 `src/engine/*.ts` 的 8 个转发壳证明这套机制在跑）。按同一模式提 `packages/store`，
同时按 session/tenancy、modeling、execution、serve、knowledge 拆成 5 个 store —— 拆分与收敛一次做完。

### 6. Mock 种子被打进生产包

**证据**：两个 `stores/app.ts:37` 都是**顶层静态 import**（不是动态 import、没有 `import.meta.env.PROD` 判断）；
`mock/seed.ts` 各 792 行。构建产物里可直接搜到演示租户标识：

```
dw-org/ui/dist/assets/index-DUEPVLFc.js    1.6M   t-xinghe 命中
dw-model/ui/dist/assets/index-C7Jpkixj.js  2.0M   t-xinghe 命中
```

**影响**：演示租户/账号信息随前端产物对外发布；同时给主 chunk 增加可观体积。

**建议**：改动态 `import()` 且按 `import.meta.env.PROD` 剔除 —— 两行改动。

### 7. 两个 UI 的 vite 配置没有 build 段

**证据**：

```
dw-org/ui/vite.config.ts              32 行   build段:0   manualChunks:0
dw-model/ui/vite.config.ts            32 行   build段:0   manualChunks:0
dw-lineage/sql-tools-vue/vite.config.ts  112 行  build段:2   manualChunks:1   ← 已有正确做法
```

结果是两个 UI 的主 chunk 分别 1.6M / 2.0M，无 vendor 拆分、无压缩、无 sourcemap 策略、base 不可配。

**建议**：直接抄 lineage 那份（它已有 `manualChunks` + `chunkSizeWarningLimit:99-110` + `base: VITE_PUBLIC_PATH:66`）。

### 8. 根 `build` 脚本漏掉两个前端

**证据**：根 `package.json:25` → `"build": "npm run build -w @dw-ai/ui"`。
`@dw-ai/ui` 是 **dw-model/ui**。`dw-org/ui` 与 lineage 没有根入口（仅 `package.sh` 间接调）。

**影响**：任何用 `npm run build` 的自动化（包括第 2 条要加的 CI）都会**静默漏掉租户管理前端**。

**建议**：补 `build:org` / `build:lineage`，`build` 聚合三者。
（同时修：`package.json:8` 的注释提到 `test:lineage:ui`，该脚本**实际不存在**。）

### 9. 三套互不兼容的请求封装

**证据**：`dw-org/ui/src/api/client.ts` 与 `dw-model/ui/src/api/client.ts` 是手写 fetch，
两者 diff 207 行 / 各 631、648 行；`dw-lineage/sql-tools-vue/src/utils/request.ts` 用 axios。
鉴权、token 刷新、空转 TTL（`client.ts:35-90`）各写一遍。

**建议**：先统一到一套（建议以 lineage 的 axios 封装为基准，它同时被 `stores/tenant.ts` 等消费），
再考虑是否下沉到 `packages/`。

### 10. 类型检查与 lint 的现状（部分是误判，部分是真空）

**正面**：5 个 `tsconfig.json` **都开了 `strict: true`**；三个 UI 的 `build` 已包含
`vue-tsc --noEmit`（org `package.json:8`、model `:9`、lineage `:16`）；全仓 **0 处 `@ts-ignore`**。

**真实缺口**：

- 全仓（除 node_modules）**0 个 `.eslintrc*` / `prettier.config.*`**，
  但 `dw-lineage/sql-tools-vue/package.json:19-21` 声明了 `lint` 与 `check-format`，
  且装了 6 个 eslint 插件（`:37-50`）—— **这两个脚本必然失败**；
- `dw-model/rules/tsconfig.json` 是空文件（1 行）且无 typecheck 脚本；
- `dw-lineage/package.json:17` 有 `build:no-check`，可绕过类型检查；
- 根 `package.json` 无 `typecheck` / `lint` / `test:ui` 入口。

### 11. 血缘 `services/api.ts` 约 1/3 是死代码

**判别与抽样验证**（对文件名+导出名做全目录引用计数，排除定义处自身）：
`getDialects` / `validateSql` / `getCatalogStats` 全仓引用数均为 **0**。

完整清单：零调用函数 13 个 —— `getDialects:67`、`getTableLineage:75`、`validateSql:83`、
`getSqlKeywords:91`、`getSchemas:121`、`getLineageDataFromMate:136`、`getMetaTable:324`、
`syncMetaTables:416`、`deleteMetaColumn:448`、`listCatalogSchemas:526`、`getCatalogStats:629`、
`listCatalogCatalogs:1034`、`listMetaSyncJobs:1111`；零引用类型约 20 个
（`DialectInfo`、`FailedStatement`、`LineageResponse`、`CatalogStats`…）。
在 1228 行里约占 1/3。

> 说明：这 13 个函数**对应后端端点是否存在**需再确认 —— 若后端有、只是前端没接，
> 那是「待接功能」而非死代码，处理方式不同。建议清理前用 OpenAPI 文档交叉核对一次。

### 12. 其余前端问题

- `dw-lineage/sql-tools-vue/src` 有 **8 处 `console.log`**（org/model 为 0）；
- 大文件与模板逻辑混杂：`pages/meta.vue` 1364 行（script 751 / template 13 / style 237）、
  `components/LineageGraph/index.vue` 878 行（script 822）、`pages/map/analyze.vue` 931、`pages/overview.vue` 870；
- `(syncForm as any)[key]` 这类绕过在 `meta.vue:235,765,866,869` 与
  `RemoteMetaBrowser/index.vue:27,294,314` **复制了两份**；
- `dw-model/ui` 有 30 个文件铺 `a-table`，但**没有统一的分页/列 hook**；
- 依赖：`xlsx@^0.18.5` 在两个 UI 的 `package.json` 里声明，但两 UI 源码**零 `from 'xlsx'`**
  （仅在 `pages/spec/io.vue:47,176` 当扩展名字符串），真正需要它的是 `packages/engine` → 应改传递依赖；
  `ant-design-vue` 版本漂移：org/model `^4.2.6` vs lineage `~4.0.7`。

---

## 四、P1 后端

### 13. `dw-common` 的边界靠人眼守，且它自己零测试

**证据（边界已开始漂移）**：`TenantContext.java` 两份 **md5 完全相同**
（`4d4dd3b3…c0`）**却没收进 `dw-common`**；`TenantFilter.java` 两边的注释已经不一致
（org `:166-171` vs model `:166-169`）；`AuthService` 已分叉（model 版 `:197-299` 多出 warehouse 分支）。

说明执行报告里那条判据「**只有两侧逐字节相同才有资格搬进来**」**没有任何自动校验**，
下次谁也不会真的去跑 md5。

**dw-common 零测试**：`dw-common/pom.xml` 无测试依赖、无 `src/test`。
而它的 `meta.entity.*` 被 org 的 `TenantAdminService`、model 的 `AccessService` 直接消费 ——
**它一改，两个服务一起坏，却没有任何测试能告诉你**。
`JsonbStringTypeHandler.java:18-28`、`Jsons` 都是纯函数，补单测接近零成本。

**建议**：
- 给「逐字节相同」加个校验（CI 里对候选清单跑一次 md5 比对，不一致就提示迁移或确认分叉）；
- dw-common 先补纯函数单测，再考虑把 `TenantContext` 这类已确认相同的收进去。

### 14. 上帝类

| 类 | 行数 | 承担职责 |
|---|---|---|
| `dw-org/.../TenantAdminService.java` | 717 | 租户 + 用户 + 项目 + LLM 密钥 + 外观偏好，注入 **12 个 Mapper**（`:48-60`） |
| `dw-lineage/.../util/SQLLineageMerger.java` | 781 | SQL 解析 + 血缘合并 |
| `dw-lineage/.../persistence/JdbcLineageCatalogRepository.java` | 746 | 目录仓储 |
| `dw-lineage/.../persistence/JdbcStatsRepository.java` | 697 | 统计仓储 |
| `dw-model/.../meta/ProjectService.java` | 545 | 项目领域服务 |

**建议**：优先拆 `TenantAdminService`（717 行 / 12 个依赖是量级最大的耦合），
按「租户 / 用户 / 项目 / 密钥 / 偏好」拆成 5 个服务。**不拆模块** —— 执行报告已论证过，
拆模块换不到部署自由度，只多出跨模块调用成本。

### 15. 异常处理三服务不统一

**证据**：lineage 有完整的 `GlobalExceptionHandler.java:29-202`（12+ handler）；
org / model 只有 `ApiExceptionHandler.java:13,20`（2 个 handler），其余异常落到 Spring 默认 JSON。
同一个错误在三个服务里返回**不同形态的响应体**，前端要写三套解析。

**建议**：把 lineage 的那份提到 `dw-common`，三服务共用；差异部分用 `@ControllerAdvice` 扩展点保留。

### 16. 连接池与时区零配置

**证据**：三份 `application.yml` 全文搜索 `maximum-pool-size` / `serverTimezone` / `connection-timeout`
—— **三份均无**。池大小吃 Hikari 默认 10，MySQL 时区取决于连接串运气。

**建议**：至少在 `dw-common` 里给一份共享的 Hikari 默认值，各服务只覆盖差异。

### 17. 后端其余问题

- **死代码**：`TenantFilter.mapOrg` 两份均零调用（org `:213-220`、model `:212-219`）；
  `dw-lineage/sql-tools/pom.xml:27` 的 `<jackson.verion>` **拼写错误**且全文件无引用（应为 `jackson.version`，
  当前靠 Boot 托管版本生效，属残留，删掉或改正）；
- **`System.out`**：`dw-org/.../SeedMain.java:46`、`dw-model/.../SeedMain.java:46`（`SqlCli` 是 CLI，属合理豁免）；
  未发现 `printStackTrace`，**未发现密码/令牌入日志**；
- **正面确认**：controller 层**无**直连 Mapper/JdbcTemplate；出站 HTTP 仅 LLM 调用
  （`AiService.java:37`）与合规的 `/internal/v1/*` 通道；lineage 原生 SQL 的 `tenant_id` 覆盖 176 处、
  抽查无漏；`TenantFilter` 在 `:138-140`、`TenantInterceptor` 在 `afterCompletion:122-126` 均正确清理 ThreadLocal。

---

## 五、P2 仓库治理

### 18. `docs/product/versions` 占 761 文件 / 14 万行

**证据**：`docs` 被跟踪 **909** 个文件，全仓共 **1740** 个 —— docs 占 **52%**。
其中 `docs/product/versions/` 的 `.ts/.vue/.js` 共 **761 文件 / 140,069 行**。

各版本：0.1.0 67/10,230、0.1.1 91/14,683、0.1.2 1、0.1.3 111、0.1.4 119、
0.1.5 279、0.2.0 282。`docs/product/run-proto.mjs:6` 默认版本是 **0.2.0**（可传参切版本）。

**重复度**：0.1.5 与 0.2.0 下**同时存在 `org/` 与 `prototype/` 两个子目录**，彼此大量逐字节相同
（`0.2.0` 内两目录 95 个相同文件、`0.1.5` 内 101 个；`0.1.5` 的 `mock/seed.ts` 两侧 md5 一致）。

**建议**：0.1.0~0.1.4 迁 `docs/archive/`（或压成快照），`org/` 与 `prototype/` 二选一保留。
可减约 **500 个文件 / 数万行**，且不影响 `npm run proto`。

### 19. 版本号 7 处不一致

| 位置 | 版本 |
|---|---|
| 根 `package.json` / `dw-org/ui` / `dw-model/ui` | 0.1.3 |
| `dw-common` / `dw-org/api` / `dw-model/api` 的 pom | 0.1.3 |
| **根 `pom.xml`** | **0.2.0-SNAPSHOT** |
| **`dw-lineage/sql-tools/pom.xml`** | **1.0-SNAPSHOT** |
| **`dw-lineage/build-release.sh`** | **1.0.0**（产物 `sql-lineage-1.0.0.tar.gz`） |
| `dw-model/rules` / `packages/engine` | 0.1.0 |
| `dw-lineage/sql-tools-vue` | **0.0.0** |
| compose image tag | 固定 `0.1.3`（lineage 无 tag） |

**影响**：`README` 里 `dw-org-0.1.3.tar.gz` 只对 org/model 成立，lineage 无对应说明。

### 20. README 端口与 docs 冲突

**证据**：`README.md` 写组织 UI **5171** / 仓建设 **5172**，与
`dw-org/ui/package.json`（`--port 5171`）、`dw-model/ui`（`--port 5172`）一致 → **README 是对的**。
但 `docs/product/README.md:19` 写「5174 / 18080、5173 / 18081」，
`docs/tech/07-0.2.0.md:40-42` 写「dw-org/ui → 5174、dw-model/ui → 5173」 → **两处 docs 过期**。
照 docs 起服务会打不开或串端口。

### 21. 构建脚本与 compose 重复

- `dw-org/package.sh`(184 行) 与 `dw-model/package.sh`(181 行) 归一化后仅 **39 行差异**（≈79% 相同），
  差异只是 NAME / workspace / 端口文案 → 建议抽 `packaging/package-lib.sh`；
- 三套模块 `docker-compose.yml` 与根 compose 大量重复：根 compose 的 org-api / model-api 段
  与 `dw-org/docker-compose.yml`、`dw-model/docker-compose.yml` 环境变量逐项重复；
  `dw-lineage/docker-compose.yml` 又是第三套（`image: sql-tools-backend` 无 tag、端口写死 8080）。
  **已在漂移**：`ORG_BASE_URL` 在根 compose 是 `http://org-api:8080`，在 dw-model 单机 compose 是
  `http://host.docker.internal:18080`。

### 22. 仓库卫生杂项

- `docs/.workbuddy/memory/2026-09-21.md` **被 git 跟踪**（`M`），`2026-09-22.md` 是新增 ——
  agent 工作记忆不该入库（同理 `.cursor/` 未被忽略）；
- 根 `data/dw_org.mv.db` 是残留运行产物（`.gitignore` 的 `data/` 规则没覆盖到根 `data/` 这一层，
  或它是在规则生效前加进去的）；
- **双锁文件并存**：根 `pnpm-lock.yaml`（仅 9 行空壳）与 `package-lock.json` —— 二选一；
- `dw-lineage/PHASE*.md`（6 个）与 `docs/tech/optimization-*.md` 内容重叠，
  `PHASE3_PROGRESS.md` 停在 2026-08-22（仍描述 `localhost:4224` 的 dbx 链路）；
- 测试数在文档里有三个版本（`optimization-plan.md` 记 360、`execution-report` 记 366/414、
  实际 `@Test` 计数为 org 30 / model 34 / lineage 420）—— 建议以 surefire 报告为单一出处，
  文档只写「见构建产物」而不抄数字。

---

## 六、已验证的正常项（不必再改）

避免下一轮重复调查，这些**查过了、是对的**：

- `HEAD` 自洽，能构建；「HEAD 引用不存在的 dw-common」不成立；
- 三个后端 Spring Boot **3.3.13 / Java 21 已统一**（P1-5 生效），无版本冲突；
- 无被跟踪的大于 1MB 的文件；`dist` / `release` / `target` / `*.db` 均在 `.gitignore` 内；
- `CORS_ORIGINS` 默认非 `*`；`MODULE_TOKEN` 已 fail-fast；`/internal/v1/**` 有模块令牌网关；
- lineage standalone 的 `permitAll`、`/api/tenants` 不解析租户上下文 —— 均有意为之且注释完整；
- lineage 原生 SQL 的 `tenant_id` 覆盖完整（176 处）；ThreadLocal 无泄漏；
- 前端 `strict: true` 全覆盖、0 处 `@ts-ignore`。

---

## 七、建议执行顺序

**第一批（今天，成本近零、收益最大）**
1. 提交本轮变更（拆 3 个 commit）—— 其余一切的前提
2. 加最小 CI workflow
3. 修根 `build` 脚本漏后端前端（第 8 条）—— 否则第 2 条会漏构建
4. Mock 种子改动态 import（第 6 条）
5. 版本号与 README/docs 端口对齐（第 19、20 条）

**第二批（本迭代）**
6. 环境变量密钥 fail-fast（第 3 条）
7. 确认并文档化 `warehouse-multi` 豁免（第 4 条）
8. `dw-common` 补纯函数单测 + 加「逐字节相同」校验（第 13 条）
9. 两个 UI 抄 lineage 的 vite `build` 段（第 7 条）

**第三批（择机）**
10. 前端 store 收敛成 `packages/store` 并拆 5 个（第 5 条）—— 前置：先有 `typecheck`/`lint` 入口
11. 抽 `packaging/package-lib.sh`、compose 用 `include:` 去重（第 21 条）
12. docs 历史版本归档（第 18 条）—— 需产品侧确认
13. 拆 `TenantAdminService`（第 14 条）、统一异常处理（第 15 条）

> 与首轮方案的差异：首轮的判断是「最大的债是多了一份」，**本轮实测的结论是
> 「最大的债是这一份还没落盘、且没人在自动验证它」**。技术收敛（P2-6/P2-7）可以继续等，
> 但第 1、2 条每多放一天，风险就多累积一天。
