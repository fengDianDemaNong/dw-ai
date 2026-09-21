# ADR-0003: dw-org 清理建模领域死代码；共享模块抽取暂缓

## Status

Accepted（2026-09-21）

## Context

`dw-org`（租户管理）与 `dw-model`（智仓）由早期单体拆分而来，残留两类问题：

1. **dw-org 混入了 dw-model 的建模领域代码**。dw-org 的 schema 只有 14 张表（组织与平台），
   但 meta 包里带着 `WarehouseTable`、`TableColumn`、`TableVersion`、`Domain`、`DataGrade`、
   `LayerRule`、`WordRoot`、`ModelingDraft` 共 8 组 entity + mapper —— 这些表在 dw-org 库里
   不存在，且这 8 组只互相引用、不与任何业务代码相连，是纯死代码。
2. **两侧共享 32 个「组织与平台」领域类**（13 entity + 13 mapper + 5 support + 1 dto），
   逐字节相同 —— 改一处要改两处，存在漂移风险。

另一个关键前提：**dw-org 与 dw-model 都没有任何测试**（`src/test` 不存在），
任何重构都缺少回归保护。

## Decision

本轮**只做清理，不抽共享模块**：

1. 删除 `dw-org/api` 里 8 组建模 entity + mapper（16 个文件）。删除后 dw-org 正好
   14 个 entity 对应 14 张表，领域边界干净。
2. 共享的 32 个类**暂不抽成 `dw-common`**。抽取本身已验证可行（实现后两侧编译通过），
   但它会把构建链路一并拖进来：
   - `dw-org/api/Dockerfile`、`dw-model/api/Dockerfile` 的构建上下文要从各自 api 目录
     改成仓库根；
   - 还要改 `docker-compose.yml`、`package.sh` ×2、`package.json` 的 dev 命令；
   - 此后每次改共享类都要先 install 共享模块。
   在**两个模块零测试**的前提下，这个改动面与当前收益不成比例。

## Consequences

已获得：

- dw-org 领域边界清晰（14 张表 ↔ 14 个 entity），不再背着不属于它的建模代码；
- 消除了「代码引用了 schema 里不存在的表」这类隐患。

仍存在：

- 32 个平台领域类仍是两份副本，改一处要改两处 —— 重复的真实代价会在
  「组织/平台领域发生变更」时兑现。

后续若要抽共享模块，建议顺序：

1. 先给 dw-org / dw-model 补最小测试（否则重构无保护，这是当前最大的短板）；
2. 再抽 `dw-common`，同步改构建链路（Dockerfile / compose / package.sh / package.json）；
3. 若长期方向是「dw-org 是组织数据的唯一所有者」，更彻底的做法是让 dw-model 改走 API 访问、
   直接删掉 dw-model 里的组织表与对应 entity/mapper —— 那是独立的一次架构演进，另立 ADR。
