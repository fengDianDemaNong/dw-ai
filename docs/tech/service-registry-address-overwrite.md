# 服务注册：心跳无条件覆盖手工登记的地址

> **【已解决 · 2026-09-24】** 心跳与推送一起退役了：项目同步改成模块按需拉取
> （`docs/tech/06-0.1.5-routes.md` §7），组织不再需要知道模块的**后端**地址，
> 于是「模块自报地址覆盖人工登记」这条路径整个消失 —— `service_registry` 现在只由人工登记写。
> 那一列 `base_url` 以及 `version` / `seen_at` 保留但无写入源，新登记的是
> `frontend_url`（产品的**页面**地址，给门户嵌入用）。
> 本文保留作诊断记录：它说明的是「同一行记录被两个写入方争抢、且没有来源标记」这类
> 设计的失效方式，与具体字段无关。

> 2026-09-23 诊断。**本次只记录，未改任何代码或配置。**
> 结论一句话：组织平台「服务注册」页上的地址**永远以模块自报的 `SERVICE_BASE_URL` 为准**，
> 手工登记填的值会在 **30 秒内**被心跳盖掉；而三个模块的默认值都是 `127.0.0.1`，
> 所以**跨机部署时这个字段必定是错的**，且失败是静默的。

---

## 1. 现象

在组织平台「平台管理 → 服务注册」手工登记 `metadata`（数据地图）为
`http://192.168.12.136:18082`，过一会儿列表里变回 `http://127.0.0.1:18082`。
`warehouse`（仓建设）同理，被改回 `http://127.0.0.1:18081`。

提问的原话还包括一句关键约束：**「org、model、lineage 三个服务不一定部署在同一台服务器」**。
—— 这正是问题的要害，见 §4。

## 2. 机制：两个入口写的是同一行记录，且没有「来源」的概念

| 入口 | 代码位置 | 动作 |
|---|---|---|
| 页面「手工登记」 | `dw-org/api/.../meta/PlatformController.java:113-122`（`POST /api/platform/services`） | `registry.put(product, version, baseUrl)` |
| 模块心跳 | `dw-org/api/.../internal/InternalController.java:77-94`（`POST /internal/v1/registry/heartbeat`） | `registry.put(product, version, baseUrl)` ← **同一个方法** |

`ServiceRegistry` 的类注释自己就写明了语义（`dw-org/api/.../internal/ServiceRegistry.java:18`）：

> 每个产品只留最新一条登记（库 + 内存）。**心跳覆盖写，不记流水。**

`put()`（同文件 `:45-51`）是 `byProduct.put(code, e)` + `persist(e)`，`persist` 里对已有行走
`mapper.updateById`，**无条件覆盖 `baseUrl` 与 `seenAt`**。表里没有「这条是人填的还是心跳报的」这一列，
所以实现层面根本不存在「手工登记优先」这个概念 —— 手工登记只等于「替模块报了一次」。

心跳周期是 30 秒，两个模块一致：

- `dw-lineage/api/.../internal/LineageHeartbeat.java:21` — `@Scheduled(fixedDelay = 30000)`
- `dw-model/api/.../internal/WarehouseHeartbeat.java:21` — 同上
- 另有一次 `ApplicationRunner.run()`，即**启动瞬间就报一次**

所以「一会儿就变」的窗口是 0～30 秒。

## 3. `127.0.0.1` 是模块「自报」的，不是组织探测出来的

心跳里的 `baseUrl` 直接取自模块自己的配置（lineage `internal/OrgClient.java:52`
`String self = blankToNull(props.serviceBaseUrl())`，`:58` `body.put("baseUrl", self)`），
模块**不会**探测自己的真实网卡地址。

| 模块 | 自报值的来源 | 默认值 | 安装包里的实际情况 |
|---|---|---|---|
| lineage | `application-multi.yml:8` | `${SERVICE_BASE_URL:http://127.0.0.1:18082}` | `dw-lineage/packaging/conf/env.sh` 里**只有 `METADATA_SECRET_KEY` 一项**，没有 `SERVICE_BASE_URL` → 安装包部署必定走 127.0.0.1 |
| model | `application.yml:40`（**没有 multi profile，三种模式共用**） | `${SERVICE_BASE_URL:http://127.0.0.1:18081}` | `dw-model/packaging/conf/env.sh:20` **硬编码** `SERVICE_BASE_URL=http://127.0.0.1:18081`；`packaging/conf/application.yml:18` 同值 |

`dw-org` 自己**不发心跳**（全仓库只有 `InternalController` 的接收端点），所以列表里只有两个模块。
`ModuleSyncService` 里 `if ("org".equals(e.product())) continue;` 只是防御性的。

> 配置默认值本身在开发机上是对的 —— 三个服务同机跑时 `127.0.0.1` 恰好可达。
> 这正是它一直没被发现的原因。

## 4. 为什么这在跨机部署下是**功能缺陷**，而不是显示问题

`baseUrl` 不是给人看的展示字段，它是**组织回连模块的地址**。组织把项目镜像推给模块时，
直接拿它当 RestClient 的 baseUrl（`dw-org/api/.../internal/ModuleSyncService.java:59-81`）：

```java
RestClient.builder().baseUrl(e.baseUrl().replaceAll("/$", ""))   // ← 注册表里那个值
    .put()
    .uri("/internal/v1/projects/{code}", code)
```

链路是：模块心跳注册 → 组织在 `catchUp` 时调 `syncProjectsTo`（`InternalController.java:90-92`）
→ 逐个项目 `PUT {baseUrl}/internal/v1/projects/{code}`。

跨机部署时 `baseUrl = http://127.0.0.1:18082` 指向的是**组织自己那台机器**上的 18082 端口：

- 那里通常没有模块在监听 → `Connection refused`；
- 异常被 `ModuleSyncService.put` 的 `catch` 吞成一条 **`log.warn`**（`:78-80`，
  `同步项目 {} 到 {} 失败`），**接口层面没有任何报错**；
- 页面上的表现是模块里「还没有可进入的项目」/「租户编码未同步」——
  与 `pending-decisions.md` P2-3 描述的症状完全一样，但根因不同（那条是 `MODULE_TOKEN`）。

也就是说：**项目镜像同步静默失败，而组织侧的注册表看起来一切「在线」**（`seenAt` 一直在刷新，
状态列永远是绿的，因为心跳是模块主动发出来的、走得通——**心跳方向没问题，出问题的是反向回连**）。

这是本次诊断最值得记住的一点：注册表的「在线」只证明**模块 → 组织**通，
不证明**组织 → 模块**通。两者在这个设计里用的是同一个地址，但只有一个方向可达时没有任何提示。

## 5. 次生效应：地址来回跳会反复触发全量推送

`InternalController.java:86-88` 的 `catchUp` 条件是：

```java
boolean catchUp = prev == null
    || !baseUrl.equals(prev.baseUrl())        // ← 地址变了就全量推一次
    || Duration.between(prev.seenAt(), Instant.now()).toSeconds() >= 120;
```

手工登记改成 `192.168.12.136` → 立刻触发一次全量推；30 秒后心跳把地址改回 `127.0.0.1`
→ **又**触发一次全量推。只要有人在页面改地址，就会来回推。
（推送本身是 upsert，幂等，不会写坏数据，代价是无效请求与日志噪音。）

## 6. 影响面

| 场景 | 影响 |
|---|---|
| 三服务同机（当前开发环境） | 无实际影响，`127.0.0.1` 恰好可达 |
| 组织与模块分机 | **项目镜像同步静默失败**，模块侧看不到租户/项目 |
| 手工登记想覆盖 | 无效，30 秒内被心跳改回 |
| 注册表「在线」状态的可信度 | 只反映模块 → 组织的方向，反向不可达时仍显示绿色 |

另有一个次要陷阱：`LineageProperties.serviceBaseUrl()`（`:219-222`）与
`DwaiProperties.serviceBaseUrl()`（`:89-92`）都有 `publicBaseUrl` 兜底 ——
若在 multi 下把 `SERVICE_BASE_URL` 显式设成空串，lineage 会兜底成
`PUBLIC_BASE_URL`（multi profile 默认 `http://127.0.0.1:5173`），
即把**前端地址**当作回调地址报上去，错得更远。

## 7. 可选修法（未拍板，按代价从小到大）

1. **运维约定（不改代码，立刻可用）**：起模块时显式给对外可达地址，跨机时各填各的。
   ```bash
   SERVICE_BASE_URL=http://192.168.12.136:18082   # lineage
   SERVICE_BASE_URL=http://192.168.12.136:18081   # model
   ```
   配套：`dw-lineage/packaging/conf/env.sh` 补上这一项（现在完全没有），
   `dw-model/packaging/conf/env.sh:20` 的硬编码值改为填写位。
   —— 缺点：仍然靠人记得填，且填错没有任何提示。

2. **防呆（小改）**：服务启动时若 `serviceBaseUrl()` 仍是回环地址（`127.0.0.1` / `localhost`）
   且处于 multi，打一条 `warn`：「组织将无法回连本模块，跨机部署请设 `SERVICE_BASE_URL`」。
   可与 `pending-decisions.md` P3-9（`org-base-url` 默认值隐患）一并处理 —— 同一类问题。

3. **改语义（大改）**：给 `service_registry` 加一列记来源（`manual` / `heartbeat`），
   手工登记过的产品，心跳只更新 `seenAt`（在线状态）不覆盖 `baseUrl`。
   需要迁移脚本 + 改 `ServiceRegistry.put` 与 `InternalController`。
   —— 语义更符合直觉，但要先想清楚「模块换了地址怎么办」（需要能解除手工锁定）。
   注意：`ServiceRegistry` / `ModuleSyncService` **目前零测试覆盖**
   （只有 `InternalModuleTokenTest` / `InternalContractTest` 间接用到），任何改动都要先补网。

## 8. 复现与验证

只读观察（组织侧注册表，需模块令牌）：

```bash
curl -s -H "X-Module-Token: $MODULE_TOKEN" http://127.0.0.1:18080/internal/v1/registry
# 看 metadata / warehouse 两条的 baseUrl 与 seenAt
```

要观察「覆盖」行为本身：手工登记一个地址（页面或 `POST /api/platform/services`），
然后**连续两次**间隔 35 秒以上查注册表 —— `baseUrl` 会回到模块自报的值，
`seenAt` 每次都在刷新。

## 9. 关联

- `pending-decisions.md` P2-3（`MODULE_TOKEN` 漏配的症状与此相同，根因不同，排查时两条都要看）
- `pending-decisions.md` P3-9（`org-base-url` 默认值 `127.0.0.1:18080` 的同类隐患）
- `dw-org/api/.../internal/ModuleSyncService.java`（回连方，全仓库唯一消费 `baseUrl` 的地方）
