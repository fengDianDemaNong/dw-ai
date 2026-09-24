# ADR-0009：前端收敛与数据地图分层 —— 分析完成，决策待定

## Status

Proposed（**需要产品/团队决策后才能实施**，本 ADR 只交付分析与建议）

## Context

优化清单里最后两项是「P2-6 前端收敛」与「P2-7 dw-lineage 分层」。它们与前四项性质不同：

- 前三项（三模式测试、跨服务契约、构建统一、抽 dw-common）都有**客观正确的答案**，
  做完可以靠测试判定对错；
- 这两项都需要先回答一个**只有人能给答案**的问题：
  「这两个前端/这个模块**应该**长什么样」。

因此这里先把数据量清楚，把选择题摆出来，而不是先动手。

---

## P2-6 前端收敛

### 先更正一处此前的误判

上一版分析说「dw-org/ui 与 dw-model/ui 有 23 个文件逐字节相同」。逐目录看过之后，
其中 **8 个是 `src/engine/*.ts`，内容是单行 `export * from '@dw-ai/engine';`** —— 它们是
兼容旧相对导入（`../engine/modeling` 等）的转发壳，**不是重复代码**。

> **教训**：用「同名 + 内容一致」数重复度会骗人 —— 一模一样的转发壳会被算成重复，
> 而真正的引擎只有 `packages/engine` 一份。凡是统计结论，都要抽样打开看一个。

**修正后的数字：真正逐字节相同的只有 15 个文件，单份 2,042 行（两处合计 4,084 行）。**

### 实测数据

| 前端 | 规模 | 测试 |
|---|---|---|
| `dw-org/ui` | 23 vue / 31 ts / 9,560 行 | `e2e/workbench.spec.ts` |
| `dw-model/ui` | 57 vue / 32 ts / 15,433 行 | `e2e/workbench.spec.ts` |
| `dw-lineage/sql-tools-vue` | 39 vue / 29 ts / 16,525 行 | **无** |

**逐字节相同的 15 个文件**（单份行数）：

```
823  pages/org-users.vue
359  pages/sys/knowledge.vue
301  components/KnowledgeManual.vue
132  pages/admin/users.vue
123  pages/sys/roles.vue
  +   auth/casdoor.ts, components/PageHeader.vue, components/SqlBlock.vue,
      config/{aiPrompts,grades,iam,knowledge}.ts, main.ts,
      pages/no-project.vue, types/index.ts
```

**已分叉的 28 个文件**（差异行数 top）：

```
347  components/AppNav.vue        217  router/index.ts
183  api/client.ts               158  stores/app.ts
134  config/nav.ts                95  stores/prefs.ts
 89  pages/sys/settings.vue       64  config/product.ts
 61  config/pages.ts              60  components/UserPanel.vue
```

页面级分布：21 个 .vue 两边同名、dw-org/ui 独有 2 个、dw-model/ui 独有 36 个。

### 判断

分叉**集中在壳层**（导航、路由、api client、store、菜单配置），这正是两个控制台
「同一底座、不同产品」的预期形态 —— 它们本来就该不同。

而相同的 15 个文件里，`pages/org-users.vue`（823 行）、`pages/sys/knowledge.vue`、
`components/KnowledgeManual.vue` 这些是**同一份实现被抄了两遍**，与产品差异无关。

### 建议方案（待确认）

**做，但只做 15 个相同文件，抄 dw-common 的做法：**

1. 新建 `packages/ui-common`（workspace 包，与 `packages/engine` 同级、同形式）。
2. **只收逐字节相同的文件**，判据与 ADR-0008 完全一致：有一行差异就不收。
3. 先从两个控制台删掉副本，改为从 `@dw-ai/ui-common` 导入。
   `pages/org-users.vue` 这类页面级组件是否适合进共享包，需要**单独判断**（见下）。
4. `src/engine/*.ts` 那 8 个转发壳：把调用点改成 `@dw-ai/engine` 后删除。
   机械改动，但**必须先让前端能跑类型检查/构建来验证**——目前没有这个前置条件（见风险）。

**收益**：4,084 行重复变成 2,042 行；`org-users.vue` 这类大文件不再需要改两遍。

**风险与前置条件（这是我不建议现在就动手的原因）**：

- 前端唯一的自动化验证是两个 `e2e/workbench.spec.ts`，没有单元测试。
  改 import 路径这种「机械改动」也必须有 `vue-tsc` + 构建来兜底，
  否则错误会以「运行时白屏」的形态出现在用户面前。
- `pages/*.vue` 进共享包需要先决定**它是不是产品无关的**。`org-users.vue` 看着通用，
  但如果它内部引用了各控制台自己的 store/api client，就会把壳层也拖进共享包 ——
  那就变成了「共享一个其实并不共享的东西」，比重复更糟。
- 三个前端的 package manager 不统一（见 ADR-0007），新共享包要先定跟谁。

**建议的下一步**：先让前端具备「能一条命令验证改动」的能力
（`vue-tsc --noEmit` 至少对两个控制台跑起来），再做收敛。

---

## P2-7 数据地图（dw-lineage）分层体检

### 实测数据

`sql-tools`：主代码 17,871 行 / **测试 12,504 行（62 个文件）**，测试比主代码 ≈ 0.70。

| 包 | 文件 | 行数 |
|---|---|---|
| `persistence` | 29 | 4,041 |
| `service` + `service/impl` | 25 | 3,724 |
| `service/metadata`（含 `dbx`） | 15 | 1,785 |
| `dto` | 44 | 1,679 |
| `controller` | 10 | 1,474 |
| `util` | 4 | 1,249 |
| `conf` / `cli` / `enums` / `exception` / `tenant` / `internal` | 26 | 1,875 |

最大的 5 个类：

```
781  util/SQLLineageMerger.java
746  persistence/JdbcLineageCatalogRepository.java
697  persistence/JdbcStatsRepository.java
533  service/impl/MetaSyncServiceImpl.java
518  service/metadata/dbx/DbxClient.java
501  persistence/JdbcLineageRepository.java
```

### 判断

**分层本身是健康的**，不需要重构：

- 依赖方向是常规的 `controller → service → persistence`，没有环形；
- `dto` / `enums` / `exception` / `conf` 边界清楚；
- **测试网是三个后端里最强的**（62 个测试文件，还有 `TenantIsolationArchTest`
  这类架构守卫），所以它反而是「最敢改」的模块。

真正的问题不是分层，而是**几个过大的类**：`SQLLineageMerger`(781)、
两个 `Jdbc*Repository`(746/697) 把「SQL 解析合并」「统计口径 SQL」全塞在一个类里。

### 建议方案（待确认）

**不拆模块，先拆大类**。理由：拆模块的收益是「能独立部署/独立扩容」，
而数据地图本来就是一个独立部署单元；拆它的内部模块只会多出跨模块调用成本，
换不到任何部署自由度。相比之下，781 行的合并算法、746 行的 JDBC 仓库是真实的维护负担。

具体建议：

1. `SQLLineageMerger` → 按「表级血缘 / 字段级血缘 / 合并策略」拆成 3 个类（有测试兜底）。
2. `JdbcLineageCatalogRepository` / `JdbcStatsRepository` → 把内嵌的统计 SQL 提取成常量或
   独立的 SQL 片段类（`JdbcStatsRepository` 的 697 行里大部分是 SQL 字面量）。
3. 若将来确要拆进程，**天然接缝是 `service/metadata`（含 `dbx`）** —— 它是
   「对接外部元数据服务（Gravitino / dbx）」这一个独立的上下文，与血缘解析几乎无耦合。

---

## Consequences

### 现状

- 这两项**没有实施**，也没有改动任何前端或 dw-lineage 的业务代码。
- 交付的是数据与选项，不是半成品。对一个需要人来定的方向，改一半比不改更糟 ——
  它会同时制造「已经动过了」的错觉和「两侧都不一致」的新状态。

### 阻塞点

- P2-6 需要：① 前端一条可验证命令；② 确认哪些 `pages/*.vue` 真的产品无关。
- P2-7 需要：确认是否接受「不拆模块、只拆大类」这个结论。

### 如果决定不做

也成立。15 个文件 4,084 行的重复，与「改错了前端没人发现」的风险相比，
保持现状是可辩护的选择 —— 前提是把 15 个文件清单记在案，`org-users.vue` 这类
需要改两遍的事实被显式接受，而不是等着有人踩坑才发现。
