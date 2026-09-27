---
name: dw-ai-scoped-test
description: 改完 dw-ai 的代码后按其影响面定向验证，默认不跑全量回归。含本仓「改动 → 测什么」对照表与各模块的命令、注意事项。用户明确说「全局测试」「全量跑一遍」时才走全量。
---

# dw-ai 定向测试

## 铁律

**默认只测本次改动涉及的功能。** 不要每次都跑「三个前端 vue-tsc + `dw-org/api` 全量
测试 + org 全量 e2e」——用户 2026-09-27 明确要求过（见记忆 `scoped-testing-by-default`）。

**先圈影响面，再决定范围**：哪些模块/文件会读到这次改的东西？共享包会让影响面横跨
多个模块。圈完按下面的表选测。

**三类改动会放大影响面，仍需扩大范围**：

1. 共享包 —— `packages/engine`、`dw-common`（后者改了**必须 install**）
2. 迁移 / 种子 SQL
3. 被守卫测试**全文扫描**的文件 —— `dw-*/ui/src/config/embed.ts`、`ProductEmbed.vue`

用户说「全局测试」「全量跑一遍」时才走全量。

## 改动 → 测什么

| 改了什么 | 测什么 |
|---|---|
| `packages/engine/**` | **所有消费方**：两个产品的 `node <mod>/ui/scripts/gen-menu.mjs` + `git diff --stat -- <mod>/ui/public/menu.json`（产物应**逐字不变**）；model 与 lineage 两侧 tsc；改了 vite 解析相关还要在浏览器里点开一次（子路径导出的坑见记忆 `vite-alias-prefix-beats-subpath-exports`） |
| `dw-org/api/**` Java | 该模块测试，**尽量 `-Dtest=XxxTest` 只跑相关的那几个类**；改动落在 `NavNode*`/挂载/守卫上时才跑全量 |
| 任意产品的 `config/embed.ts` 或 `ProductEmbed.vue` | `-Dtest=CrossServiceDesignGuardTest`（全仓副本都在它的断言里） |
| `dw-org/ui/**` | org tsc + 相关 e2e 用 `--grep` 只跑对应用例 |
| `dw-model/ui/**` | model tsc + `node scripts/gen-menu.mjs`（产物对比）+ 起 5181 点开改动的那页 |
| `dw-lineage/ui/**` | lineage tsc + `node scripts/gen-menu.mjs` + 起 5182 点开改动的那页 |
| 迁移 `V*.sql` | 拿一份**旧版本**的库文件起一次，看终端 `Migrating ... to version N`，再查目标表 |
| 前端某个组件/页面 | 只跑那个前端的 tsc；不涉及菜单契约就不必起实例 |

## 命令要点

**通用**：一切命令**不得用管道**（管道吞退出码），重定向到文件后单独 `echo "exit=$?"`。
JDK/Maven 走绝对路径，`JAVA_HOME=/Users/wang/Downloads/tools/jdk-21.0.2.jdk/Contents/Home`、
`/Users/wang/Downloads/tools/apache-maven-3.9.16/bin/mvn -o -Dmaven.repo.local=/Users/wang/Downloads/data/maven-repo`。
**绝不 `mvn clean`**（用户服务跑在 `target/classes` 上）。

**后端单类**：
```
JAVA_HOME=... /Users/wang/Downloads/tools/apache-maven-3.9.16/bin/mvn -o \
  -Dmaven.repo.local=/Users/wang/Downloads/data/maven-repo \
  -f dw-org/api/pom.xml test -Dtest=NavNodeTest > /tmp/x.log 2>&1; echo "exit=$?"
```

**前端类型**：用各模块 `package.json` 的 scripts（以实际为准），或根 `node_modules/.bin/vue-tsc`。
同样重定向 + `echo "exit=$?"`。

**e2e 选测**：
```
E2E_BASE_URL=http://127.0.0.1:5180 node_modules/.bin/playwright test \
  --config dw-org/ui/playwright.config.ts --grep "关键词"
```
- **必须带 `E2E_BASE_URL`** —— 配置默认 `5173`，那是**用户自己的实例**。
- 不能同时传 `--grep` 与 `--grep-invert`（会 "No tests found"）。
- 文件是 `describe.serial`：一条红，后面全 skip（报 "11 did not run"）——别当大面积崩。

**起验证实例**：18090-18092 + 5180-5182，独立 `DW_AI_HOME` 与库文件，三进程 `MODULE_TOKEN` 同值；
model/lineage 必须带 `ORG_BASE_URL=http://127.0.0.1:18090` 与 `ORG_UI_URL=http://127.0.0.1:5180`，
org 带 `DWAI_NAV_CANDIDATE_TTL_SECONDS=0`。**用户自己的 18080-18082 / 5171-5173 全程不碰。**
配方细节见记忆 `verify-instance-recipe`。

## 踩过的坑（省得重查）

- **e2e 假红**：跑 `workbench.spec.ts` 前先把库还原成「只有种子」。库里有**挂载的产品节点**
  会让 `:733` / `:766` 必红，报错长得像回归。见记忆 `org-e2e-assumes-empty-project-shell`。
- **lineage 的 tsc 本来就红**：`dw-lineage/ui/src/components/Tour/index.vue` 有 2 条既有类型错
  （`createVNode('img', …)` 对不上 `TourProps['steps']`）——不是本次引入，别去修。
- **改了 `dw-common` 必须 install**，否则测试绿但服务起不来。
- **清进程只按 PID**，不要 `pkill -f vite`（会连用户自己开的 dev server 一起杀）。
- **起 vite 直接调二进制**（`node_modules/.bin/vite`），`npm run` 的两层转发会吃掉追加参数。
- zsh 下 `grep --include=*.vue` 会被展开报错，要写 `--include='*.vue'`。

## 汇报口径

定向测完就报，**明确说测了什么、没测什么**。若因影响面判断而**没跑**某块（例：只改了
org 前端所以没跑后端），把那句「未跑 X，因为 Y」讲出来，让用户能判断要不要补。
