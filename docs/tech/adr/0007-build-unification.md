# ADR-0007：统一后端构建入口与版本对齐

## Status

Accepted —— **Superseded in part by [ADR-0014](0014-frontend-package-manager-unification.md)**。
被取代的只有「前端保留双包管理器」这一条（原文见下方 Consequences 末条，保留作当时判断的记录）；
后端的构建统一与版本对齐部分仍然有效。

## Context

三个 Java 后端此前是三个互不相识的 Maven 工程：

```
mvn -f dw-org/api/pom.xml test
mvn -f dw-model/api/pom.xml test
mvn -f dw-lineage/sql-tools/pom.xml test
```

仓库根没有聚合 pom。这带来三个具体问题：

1. **没人能回答「整个仓库还编译得过吗」。** 只有逐模块跑才知道，而实际改动经常横跨多个模块。
2. **Spring Boot 版本漂移了。** dw-org / dw-model 是 `3.3.13`，dw-lineage 停在 `3.3.11`。
   两个 patch 版本共存意味着同一个 CVE 修一次要改两处、而且很容易漏一处。
3. **构建入口散落。** 根 `package.json` 有 8 个 `dev:api:*` / `package:*`，Java 侧的
   `test` 却没有对应入口；`dw-lineage/sql-tools-vue` 用 pnpm，是 workspace 之外的孤岛。

## Decision

### 1. 版本对齐到 3.3.13

dw-lineage 的 `spring-boot-starter-parent` 与 `spring-boot.version` 属性都改为 `3.3.13`。
验证：`RunModeSmokeTest` + `FlywayFreshInstallTest` + `MigrationDialectTest`（22 个用例）全绿。

### 2. 根聚合 pom（**聚合，不是继承**）

新增仓库根 `pom.xml`，`packaging=pom`，只声明 `modules`：

```xml
<module>dw-common</module>        <!-- 共享模块排在前面 -->
<module>dw-org/api</module>
<module>dw-model/api</module>
<module>dw-lineage/sql-tools</module>
```

**刻意不做父 pom**：三个模块真实父是各自的 `spring-boot-starter-parent`，版本必须各自看得见 ——
一处升级就应当是一处改动。把父关系挂到根 pom 会多一层间接，唯一好处只是少写一行 `<version>`。

### 3. 统一脚本入口

根 `package.json` 增加：

```
test:api            mvn -f pom.xml test          # 全部后端
test:api:org|model|lineage                       # 单个后端
build:api           mvn -f pom.xml -DskipTests package
```

### 4. 前端 workspace 边界保持不动

`dw-lineage/sql-tools-vue` **不**并入 npm workspaces：它有自己的 `packageManager: pnpm@11.22.0`
和独立 `node_modules`，并进去会被 npm 的提升机制覆盖，属于**制造故障而不是统一**。
改为在根 `package.json` 的 `comments` 里写明这条边界与原因。

## Consequences

### 更容易

- `npm run test:api` 一条命令覆盖全部后端，CI 不再需要维护三条路径。
- Spring Boot 版本只有一种，安全补丁一处生效。
- **为抽 `dw-common` 铺了路**：Maven 聚合让 `dw-common` 与使用方在同一 reactor 里解析，
  不需要先手工 `install`（这是 ADR-0003 当初回退抽取的直接原因之一，见 ADR-0008）。

### 更困难 / 需要接受的代价

- 根 pom 的 `modules` 是一份需要维护的清单：新增后端模块忘了加，聚合构建就漏掉它。
- **单模块构建多了一个前置**：`mvn -f dw-org/api/pom.xml test` 现在要求 `dw-common`
  已在本地仓库（`mvn -f dw-common/pom.xml install`）。这是抽共享模块的固有成本，
  已在 Dockerfile 与 `package.sh` 里显式处理（见 ADR-0008），并在两处都写了注释说明。
- 前端两个包管理器（npm + pnpm）的现状被保留而非消除。它确实是历史包袱，
  但统一它的收益（少一个 lock 文件）远小于风险（重装依赖树、动构建产物）。
  如果将来要统一，应当是一次独立、单独验证的改造。

## 备注

`dw-model/rules` 是 TS 包（`@dw-ai/rules`），不属于 Java 聚合范围；它已在 npm workspaces 里。
