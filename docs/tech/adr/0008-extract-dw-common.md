# ADR-0008：抽 dw-common 共享模块（修订 ADR-0003）

## Status

Accepted — 修订 [ADR-0003](0003-dw-org-cleanup-and-defer-dw-common.md) 中「暂不抽取」的结论

## Context

ADR-0003 当时决定**只清理、不抽 dw-common**，理由是：

1. 抽取要连带改 `Dockerfile ×2`、`docker-compose`、`package.sh ×2`、`package.json`；
2. 两个模块**零测试**，改完没有任何东西能告诉你好坏；
3. 收益与改动面不成比例。

此后用户澄清了架构意图（**每个服务都要能单独拿出来用**，见 ADR-0005），
这让「共享」的性质变了：它不再是「消除技术债」，而是**把「新增一个可独立部署的服务」
的成本从「复制 32 个类」降为「加一行依赖」**。每加一个服务就再复制一份，这个边际成本才是真问题。

同时 ADR-0003 的两个前提都被解决了：

- 两个模块有测试了（ADR-0004 + ADR-0006，共 48 个用例）；
- 有了根聚合 pom（ADR-0007），共享模块可以在同一 reactor 里解析。

## Decision

抽 `dw-common`，范围严格限定为**两侧逐字节相同**的类。

### 判断标准（写进模块描述里）

> 只有 dw-org 与 dw-model 的**同一文件完全一致**才有资格搬进来；只要有一行差异，
> 说明那是服务角色差异，应当留在各自服务里。

按此标准筛出 **32 个类**：13 个 entity + 13 个 mapper + 5 个 support + 1 个 dto（`ApiModels`）。
其余 9 个同名但内容不同的类（`ProjectService`、`AccessService`、`AuthService`、`SpaIndexController`
等）**明确不搬** —— 它们依赖各自服务的建模服务 / OrgClient / 路由，是刻意的角色差异。

### 唯一障碍的解法

`JsonbStringTypeHandler` 需要判断「当前是不是 PostgreSQL」，原实现调
`com.dwai.platform.db.MetaDb.isPostgres(product)` —— 那是**服务侧**的类，
共享模块反向依赖服务侧会形成环。

解法：把判定本身（一条字符串规则）收进共享层 `support/DbVendors.isPostgres()`。
服务侧 `MetaDb` 保留自己的方法（它还要决定 Flyway 目录、seed 脚本等）。

### 依赖策略

`dw-common` 的依赖刻意最小：`mybatis-plus-core`（**不是 starter** —— 共享库不引入自动配置）、
`spring-web`、`jackson-databind`、`postgresql`(provided)。

版本用 `${project.version}`：服务与共享模块同版本发布，改版本号只改一处；
两者不一致时构建**直接失败**，不会静默降到旧版本。

### 构建链路

| 位置 | 处理 |
|---|---|
| 根聚合 pom | `dw-common` 排在最前，reactor 按序构建 |
| `dockfile ×2` | 上下文改为仓库根；先在构建容器里 `install dw-common`，之后本服务**仍按自己的 pom 独立构建** |
| `docker-compose ×3` | `context` 相应改到仓库根 / 上一级 |
| `package.sh ×2` | 打包前 `mvn -q -f $REPO/dw-common/pom.xml install`；依赖缓存哈希改为「两个 pom 一起算」 |
| 开发 | 单模块构建前需先 `mvn -f dw-common/pom.xml install`；日常用 `npm run test:api` 不受影响 |

Dockerfile 刻意**不用**根聚合 pom：那会把无关模块的源码也拖进构建上下文。
用「先 install 共享模块、再按本服务 pom 构建」两步，保持了「每个服务能独立构建」这条性质，
与开发时的操作顺序一致。

## Consequences

### 更容易

- **32 个类只存在一份**。改一处就够，不会再出现「两侧同名文件慢慢漂移」。
- **新增一个模块的成本从「复制 32 个类」变成「加一行依赖」** —— 直接服务 ADR-0005 里
  「后期一些服务做成独立的」这个规划。
- 共享模块里的每个类都是「两边必须一致」的，因此它天然是一个**契约面**：
  往里加东西需要先回答「两个服务真的都会用吗」。

### 更困难 / 需要接受的代价

- **单模块构建多了前置步骤**（先 install `dw-common`）。这是 Maven 多模块的固有代价，
  无法用配置消除；已写进 Dockerfile 注释与 `package.sh`。
- `dw-common` 与两个服务的**版本被绑定**（`${project.version}`）。发布时三者一起升。
- `mybatis-plus-core` 让共享模块对持久化框架有了编译期依赖。换取的是实体与 Mapper
  可以平移到共享层而不必包装成接口。对一个只装「两侧相同的类」的模块，这是可接受的。
- `ApiModels` 是 200+ 行的 DTO 集合，装的是**两个服务 DTO 的并集**。它之所以能进共享层，
  恰恰是因为两侧完全一致；但它也是这个模块里最容易被改坏的类（加一个字段就影响两个服务）。

### 验证

| 内容 | 结果 |
|---|---|
| 三个模块编译（根聚合 pom） | ✅ BUILD SUCCESS |
| dw-org 测试 | ✅ 25 个全绿 |
| dw-model 测试 | ✅ 23 个全绿 |
| 应用 jar 是否还残留被抽走的类 | ✅ 已无（仅保留 org 独有的 `ServiceRegistry*`） |
| 共享 jar 是否进入发布 libs | ✅ `target/lib/dw-common-0.1.3.jar` |
| 发布打包脚本 | ✅ 走到依赖缓存重建那步被环境的「批量删除确认」拦住，属环境限制，非脚本问题 |
| `docker compose config` | ✅ 有效 |

### 未验证

- `docker build` 未实际执行（需要拉取 maven 基础镜像）。

## 后续

- `dw-common` 里**不该**再放任何「只有一个服务用」的东西。判据已在模块描述里写明。
- 如果将来出现第三个使用方（例如 ADR-0005 提到的「质量/服务独立进程」），
  它应当直接依赖 `dw-common`，而不是再复制一遍。
- 若要开始抽前端共享层（ADR-0009），参照本 ADR 的判据：**只收完全相同的文件**。
