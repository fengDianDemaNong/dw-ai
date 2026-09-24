# ADR-0014: 前端包管理器统一 —— dw-lineage/ui 由 pnpm 并入根 npm workspaces

## Status

Accepted（2026-09-23 实施并单独验证）

## Context

ADR-0007 记录了三模块构建统一，但在前端留了一个**明确的例外**：

> `dw-lineage/sql-tools-vue` **不**并入 npm workspaces：它有自己的 `packageManager: pnpm@11.22.0`
> 和独立 `node_modules`，并进去会被 npm 的提升机制覆盖，属于制造故障而不是统一。

并把结论定为「保留现状，如果将来要统一，应当是一次**独立、单独验证的改造**」。本 ADR 就是那次改造。

触发它的直接原因很朴素：三个前端里两个用 npm、一个用 pnpm，开发与 CI 要记两套安装/构建/加锁路径，
`docs/tech/optimization-review-round2.md` 也点名过「双锁文件并存，二选一」。

但真正需要写下来的是**探索中发现的三个隐患** —— 它们都不是包管理器本身的问题，
而是被「两套包管理器并存」这个状态掩盖住的：

1. **`dw-lineage/ui` 有一个从未声明的依赖 `@dw-ai/engine`。**
   `src/config/iam.ts:1` 是 `import { roleHas, ... } from '@dw-ai/engine'`（值导入，真进 bundle），
   而它的 `package.json` / `pnpm-lock.yaml` / 自己的 `node_modules` **全都没有它**。
   能构建成功，靠的是 Node 解析器向上遍历到仓库根的 `node_modules/@dw-ai/engine` —— 那条软链是
   **根 npm workspaces** 装的。也就是一条**跨两个包管理器的隐式耦合**：lineage 的构建依赖
   npm 侧装出来的东西，而它自己归 pnpm 管。
   （实测佐证：迁移前的产物里能搜到 engine 独有的权限词 `spec:write` / `iam:member`，
   说明 engine 确实被打进了 bundle —— 全靠这条运气。）

2. **`dw-lineage/ui/Dockerfile` 那条链路大概率已经断了。**
   构建上下文是 `./ui`，Dockerfile 没有 COPY `packages/engine`，而源码又 import 它。
   同仓的 `dw-org/ui/Dockerfile` 上下文是仓库根、显式 COPY `packages/engine`。两者不一致。

3. **lineage 顶层 `node_modules` 有陈旧残留**：顶层的 `@antv/g-base@0.5.16`、`esbuild@0.18.20`，
   而 pnpm 实际解析给的是 `.pnpm` 里的 `@antv/g-base@0.5.1`、以及 vite 6.4.3 需要的 esbuild ≥0.24。
   **顶层读数不可信** —— 任何「只换命令、不重装」的迁移都会踩这个。

## Decision

**把 `dw-lineage/ui` 并入仓库根的 npm workspaces，删除全部 pnpm 产物。**

### 依赖与配置

- 根 `package.json` 的 `workspaces` 增加 `"dw-lineage/ui"`（成员目录保持原位置，不移动）。
- **`overrides.tslib: ^2.6.0` 搬到根 `package.json`** —— 从 `ui/pnpm-workspace.yaml` 的 `overrides` 来。
  必须是根：npm 只在 workspaces 根应用 `overrides`，放子包会被**静默忽略**（不报错，只是不生效）。
  代价是它成为仓库级设置、影响全部三个 ui；npm 没有 pnpm 的 per-importer 粒度，
  将来要给某一个 ui 单独钉版本只能换写法。
- `ui/package.json` 的 `packageManager: pnpm@11.22.0` 删除；
  **补上 `"@dw-ai/engine": "*"`**（与 `dw-org/ui` / `dw-model/ui` 同写法）—— 修掉隐患 1。
  `xlsx` 不必补：它是 engine 自己的 `dependencies`，会被提升到根。
- `ui/tsconfig.json` 加 `paths["@dw-ai/engine"]`、`ui/vite.config.ts` 加 alias 与
  `optimizeDeps.exclude`，对齐 org/model 的写法。**这两条是必须的**：没有它们，
  未安装的包会同时让 `vue-tsc` 与 `vite` 解析失败。
  此前没暴露，是因为 lineage 的 CI 与打包**都绕过了类型检查**
  （`ci.sh` 用 `build:no-check`、`package.sh` 直接调 vite）—— 这也正是隐患 1 能潜伏至今的原因。
- **`engines.node >= 22.13` 保留不动**。这个下限是 pnpm 11 要 `node:sqlite` 才设的，迁 npm 后已非必需，
  但 node 版本是独立变量，与包管理器迁移混在一起会让「构建挂了」难以归因。要放宽请单独开一次；
  Dockerfile 的 `node:22-alpine` 同理。

### 删除

`ui/pnpm-lock.yaml`、`ui/pnpm-workspace.yaml`、根 `pnpm-lock.yaml`（9 行空壳）、`.pnpm-store/`。

其中 `pnpm-workspace.yaml` 的三项配置各有归宿：`packages: ['.']` 不再需要；`overrides` 搬到根（见上）。

`allowBuilds` 的对应物是 npm 的 **`allowScripts`**（同样记在 `package.json`），
但**两者语义不同，不要当成等价物**：

| | pnpm 10+ | npm 11（实施时） |
|---|---|---|
| 默认行为 | **不执行**依赖的 install 脚本 | **执行**（脚本照跑） |
| 未审查的包 | `ERR_PNPM_IGNORED_BUILDS`，**中断安装** | 只打印一份警告清单，**不中断** |
| 配置位置 | `pnpm-workspace.yaml` 的 `allowBuilds` | `package.json` 的 `allowScripts` |

也就是说这一步**约束是放松的**，不是平移。但 npm 文档明说该字段「当前是劝告性的，
未来版本会阻止未审查的 install 脚本」，所以本次**按 pnpm 时代的口径显式批准了 esbuild**
（`allowScripts: { "esbuild": true }`，用 `--no-allow-scripts-pin` 写非 pinned 条目，
免得每次升级 esbuild 都要重新批准）；`core-js` / `fsevents` 当时就没放行，保持不放行。

> 实测：批准前 `npm install` 会把 `esbuild@0.25.12` / `esbuild@0.28.2` / `core-js` / `fsevents`
> 列为 pending，但 esbuild 依然可用（`@esbuild/darwin-arm64` 平台包随 optionalDependencies 就位，
> postinstall 的 `install.js` 只是校验/回退路径）——**这也正说明「构建能过」不能反推「脚本跑过了」**。

### Docker

`ui/Dockerfile` 整段改为 npm 形态，`docker-compose.yml` 的构建上下文由 `./ui` 改为仓库根
（`context: ..` + `dockerfile:`），与 org/model 取齐 —— 顺带修掉隐患 2。

**连带影响，容易漏**：`npm ci` 会按根 `package.json` 的 `workspaces` 数组解析**全部五个**成员目录，
少拷任何一个都会失败。因此 **`dw-org/ui/Dockerfile` 与 `dw-model/ui/Dockerfile` 也必须补
`COPY dw-lineage/ui ./dw-lineage/ui`** —— 它们原来都没拷这个目录（那时它还不是 workspace 成员）。

## Consequences

### 更容易

- 一套安装/构建/加锁路径：仓库根 `npm install` 一次，五个工作区共用；CI 与 Docker 都是 `npm ci`。
- `@dw-ai/engine` 变成**显式**依赖，不再依赖「向上遍历碰巧能解析到」。
- Docker 构建上下文与 org/model 一致，且 `packages/engine` 被真正拷进去了。
- 双锁文件消失，兑现了 `optimization-review-round2.md` 的点名。

### 更困难 / 需要接受的代价

- **`overrides` 是仓库级的**，给某一个 ui 单独钉版本没有 pnpm 那种粒度。
- **workspace 名是 `sql-tools`（不带 scope），不是目录名 `ui`** —— `-w` / `-w` 相关的命令都要用包名，
  这是最容易写错的一处。`dw-lineage/package.json` 的脚本因为不在仓库根，
  还要写成 `npm --prefix .. run <script> -w sql-tools`。
- **依赖提升改变了实际安装的版本**：`ant-design-vue` 由 ui 内嵌的 `~4.0.7` 变为与 org/model 一致的
  `^4.2.6`；`typescript` 由 5.7.2 变为根的 5.9.3。跨 minor 的 UI 依赖变更必须真机过页面，
  不能只看构建退出码。
- 仓库根的 `package-lock.json` 成为三个 ui 的共同锁，单模块回退需要一个能重建旧树的 lock。

## 不能动的东西（本次刻意保留）

- **`vite.config.ts` 的 antv shim**：`@antv/g-base@0.5.1` 的 `esm/index.js` 真的含
  `require('../package.json')`，去掉就白屏。它是**包管理器无关**的源码级插件，
  其 filter 匹配任意路径层级，npm 的提升结构下照样命中。
- `manualChunks`、`less.javascriptEnabled`、`base: VITE_PUBLIC_PATH || '/'`、
  `@antv/algorithm/lib/asyncIndex` 的空模块 alias：均与包管理器无关，原样保留。
- `package.sh:42` 的 `npx --no-install vite build --base=/`（绕过 script 直接调 vite 以强制 `--base=/`）：
  只是把安装挪到仓库根，构建命令本身不变。

## 验证

按 ADR-0007 的要求「单独验证」，且不接受「只看退出码」—— 隐患 3 正说明必须复验产物。
逐项结果见本次改造的提交说明；关键判据：

1. 仓库根 `npm install` 后 `ui/node_modules` **不再存在**（依赖已提升）；
   `node_modules/@dw-ai/engine` 软链指向 `packages/engine`。
2. `npm run build -w sql-tools`（含 `vue-tsc --noEmit`）通过 —— 这是 paths 的判据，不加必红。
3. 重装后 `esbuild` ≥0.24、`tslib` 2.x、`@antv/g-base` 为含 `require('../package.json')` 的那版。
4. 产物中 `spec:write` / `iam:member` 等 **engine 独有的权限词**仍能搜到（engine 真在 bundle 里），
   且 `require(` 无残留（shim 生效）、无硬编码后端地址。
5. 真机跑页面：表格 / `Modal.` / `message.` / 表单 / Monaco / G6 血缘图 / 元数据页，
   重点看 `ant-design-vue` 4.0.7 → 4.2.6 有无控制台告警或样式错位。
6. `dw-lineage/ci.sh` 全绿（含测试数下限守卫与产物地址扫描）、`package.sh ui` 产物结构不变、
   `docker compose build frontend` 成功（改前大概率失败）。

## 备注

- ADR-0007 中「保留双包管理器」那条已标注 Superseded in part by 本 ADR，原文保留作为当时的判断记录。
- `.trae/documents/lineage-structure-align.md` 是更早的、已被取代的结构设计稿
  （引用的 `build-release.sh` 已删除），不追。
