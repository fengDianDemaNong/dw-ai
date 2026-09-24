# ADR-0004: 为 dw-org / dw-model 补最小回归网

## Status

Accepted（2026-09-21）

## Context

复审视发现，仓库三个后端服务的测试覆盖极不均衡：

| 模块 | 主代码 | 测试 |
|---|---|---|
| dw-org/api | 69 文件 / 5,981 行 | **0** |
| dw-model/api | 88 文件 / 6,834 行 | **0** |
| dw-lineage/sql-tools | 173 文件 / 17,871 行 | 65 文件 |

dw-org（租户管理）与 dw-model（智仓）承载全部核心业务，却没有任何测试。这带来两个后果：

1. **重构无法推进**：P0-1（抽 dw-common 消除 32 类重复）做完后只能靠「编译通过」判断对错 ——
   编译不覆盖鉴权、租户隔离、数据访问这些真正会出事的地方。
2. **设计约束无处固化**：例如「dw-model 在 multi 模式下不提供登录口」这条安全边界，
   只写在 `AuthController.login` 的一个 `if` 里，没有测试守着。

## Decision

给两个模块补**最小但高价值**的回归网，不求覆盖率，只钉住最容易出事、最难人工验证的路径：

1. **Flyway 空库建表**（`SchemaBootstrapTest`）：空库启动必须建出全部表。
   覆盖迁移脚本、方言目录选择、`MetaDbEnvironmentPostProcessor` 三者的联动。
   - dw-org 断言 14 张表 + 内置管理员被种下
   - dw-model 断言 31 张表（组织 + 建模两侧）
2. **鉴权边界**（`AuthSmokeTest`）：
   - dw-org：管理员可登录 → 拿 token → 访问 `/api/auth/me` 成功；错密码被拒；匿名被拒
   - dw-model：**登录在 warehouse+multi 模式下必须 403**（固化设计，防止误开登录口）；匿名被拒

测试用**独立命名的内存 H2**（`jdbc:h2:mem:xxx;MODE=MySQL;...`），通过覆盖
`spring.datasource.url` 让 `MetaDbEnvironmentPostProcessor` 走「URL 已提供」分支，
既不污染 `./data` 文件库，又复用了与生产一致的 MySQL 兼容模式参数。

## Consequences

已获得：

- 两个模块从「0 测试」变为「有护栏」；`mvn test` 现在能验证 Flyway 与鉴权两条底线；
- P0-1 续做（抽 dw-common）不再是盲改 —— 至少边界行为有回归；
- dw-model 的「multi 模式不提供登录」从隐含约定变成可执行约束。

仍存在 / 已知代价：

- 覆盖仍很薄：项目/租户 CRUD、租户隔离、内部鉴权契约（`X-Tenant-Code` 头）等都还没有测试；
- **补测试过程中发现的真实差异**：dw-org 与 dw-model 的 `AuthController.login` 判断条件不同
  （dw-org 只看 `isStandalone()`，dw-model 还看 `isWarehouseOnly() && isMulti()`）。
  这是**刻意的角色差异**，不是 bug —— 但正因如此，两个服务的测试不能互相照抄，
  后续补测试时要分别按各自契约写。

## 后续建议

按「最容易出事」排序继续补：

1. 租户隔离：带不同 `X-Tenant-Code` 访问同一接口，不得互相看到数据；
2. 项目 CRUD：创建 → 查询 → 修改 → 停用 的完整链路；
3. 内部契约：`/internal/v1/**` 的 module-token 校验、建项目 fan-out。
