# ADR-0001: 统一包名与 groupId 为 com.dwai.lineage / com.dwai

## Status

Accepted（2026-09-21）

## Context

`dw-lineage` 由旁边仓库 `sql-lineage` 整体拷入本仓库（`dw-ai`），目录名改为 `dw-lineage`，
但代码层面的命名没有跟着走：

- Java 包名仍是 `org.qq.*`（外部仓库遗留），而 `dw-org` / `dw-model` 用的是 `com.dwai.platform`；
- Maven `groupId` 仍是 `org.example`，而另两个模块是 `com.dwai`。

放在同一个仓库里，三套命名并存会持续制造认知成本：新人看到 `org.qq` 会以为它属于另一个组织；
日志、排查、依赖坐标也无法用统一的模式去检索。这与「多模块同仓」的前提相冲突。

风险排查结论（重命名的前置条件）：项目内没有 `Class.forName`、`@JsonTypeInfo`、`@class`
等硬编码全限定类名的用法，因此重命名包不会导致序列化 / 反序列化或反射失效。

## Decision

将 `dw-lineage` 的代码命名对齐项目规范：

1. Java 包名 `org.qq.*` → `com.dwai.lineage.*`；目录 `src/{main,test}/java/org/qq` →
   `src/{main,test}/java/com/dwai/lineage`；
2. Maven `groupId` `org.example` → `com.dwai`；`artifactId` 保持 `sql-tools`
   （内部工程名，安装包 / 脚本仍按 `sql-tools` / `sql-lineage` 引用，不在本次范围）；
3. 同步更新 `META-INF/spring.factories`、`EnvironmentPostProcessor.imports`、
   `application*.yml` 的 `logging.level`、以及测试里硬编码的源码路径。

## Consequences

变得更容易：

- 三个后端模块（`dw-org` / `dw-model` / `dw-lineage`）命名统一，检索与认知成本下降；
- 日志配置、依赖坐标可用同一套模式处理。

变得更难 / 固有代价：

- 一次性全量回归（360 个测试）是必须的；重命名没有任何运行时语义变化，但编译期全链路受影响；
- 测试里**硬编码源码路径**的地方（如 `TenantIsolationArchTest` 的 `Path.of("src","main","java","com","dwai","lineage","persistence")`）
  不会被 IDE 重构或 `sed 's/org\.qq/.../'` 覆盖 —— 这类「路径分段拼接」是本次的漏网点，
  已修复并在回归中暴露。
- `release/sql`、安装包产物名里的 `sql-lineage` 未改（属于发布命名，改动会波及部署脚本），
  留作独立决策。
