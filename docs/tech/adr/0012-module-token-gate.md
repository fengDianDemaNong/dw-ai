# ADR-0012: 服务间接口门禁收紧 —— 未配 module-token 一律 401

## Status

Accepted

## Context

三个服务的服务间接口（`/internal/v1/**`）用**静态共享密钥**保护：调用方带
`X-Module-Token`，接收方比对 `security.module-token`。这个形态本身没问题，
出问题的是「密钥没配」这一支的实现。

改造前，三处的校验是同一份逻辑的三份副本：

```java
if (props.isStandalone()) return;
String expected = ...getModuleToken();
if (expected != null && !expected.isBlank()) {
    String given = req.getHeader("X-Module-Token");
    if (!expected.equals(given)) throw new ResponseStatusException(UNAUTHORIZED, "模块令牌无效");
    return;
}
// 「未配密钥」这一段：
String user = TenantContext.user();
if (user == null || user.isBlank() || "anonymous".equals(user)) {
    return;                                  // ← 放行
}
// 方法在这里就结束了 —— 真实用户走到最后也是放行
```

**当 `module-token` 为空时，这个方法等价于 `return;`。** 注释写的意图是
「未配模块令牌时，本机开发允许服务间调用」，但实现上它是个**伪装成守卫的空操作**：
那个 `if` 之后没有任何代码，两条分支都放行。

而 `module-token` 在三份 `application.yml` 里的默认值都是 `${MODULE_TOKEN:}` —— **空的**。
`/internal/v1/**` 又在 SecurityConfig 里是 `permitAll`（它不能要求用户 JWT：心跳发生在
启动时，那一刻没有任何用户登录）。三件事叠加：

> **默认配置下，`/internal/v1/**` 是公开接口。**

影响面：
- `dw-org`：`/internal/v1/context`、`registry/heartbeat`、`authz/check`、`auth/refresh|logout`
  —— 组织平台**恒为 multi**（`product=org` 使 `runMode()` 直接返回 multi），所以它永远走不到
  `isStandalone()` 那行豁免，永远暴露。
- `dw-model` / `dw-lineage`：`PUT|DELETE /internal/v1/projects/{project_code}`
  —— 项目镜像的建与删，任何人都能调。

### 证据：这不是理论推断

把修复临时回退、跑新增的 `InternalModuleTokenTest`，不带任何令牌的 PUT 返回的是 **200**，
并且**真的落库了**：

```
Status expected:<401> but was:<200>
实际: {"id":3,"tenantId":2,"code":"p_checkout","name":"结算域项目",...}
```

## Decision

**「没配密钥」必须表现为失败，而不是放行。** 三处实现统一成：

1. `standalone` 直接放行 —— 那种模式没有组织平面，不存在服务间调用（保留既有语义）；
2. **`expected` 为空 → 401**，错误信息里带上要设的属性名与环境变量名
   （例如「请设置 `dwai.security.module-token`（环境变量 `MODULE_TOKEN`）」）；
3. 比对用 **`MessageDigest.isEqual`** 做定长比较：`String.equals` 会在第一个不同的字节处
   提前返回，攻击者能按响应耗时逐字节把密钥试出来；
4. 空串头不算「提供了令牌」。

三份 `application.yml` 的 `module-token` 上方各加一条注释说明「留空 = 一律 401，
三处必须配同一个值」。

### 没有选择「启动即拒绝」的原因

考虑了另一种更严格的做法：非 standalone 且 `module-token` 为空就拒绝启动。
放弃的理由是它把「模块单跑」也一起禁掉了 —— 而「每个服务都能单独拿出来用」是明确的设计承诺。
401 已经足够「明显失败」，且保留了单跑能力。

## Consequences

### 变简单 / 变安全

- 默认部署不再是「默认开放」。忘记配置的表现从「静默可用」变成「明确 401 加一句怎么修」。
- 三处行为一致，且**各有测试守着**：
  - `dw-org/api` · `InternalModuleTokenTest`（4 条）
  - `dw-model/api` · `InternalModuleTokenTest`（5 条，含「被拒的请求不能留下项目」）
  - `dw-lineage/sql-tools` · `InternalModuleTokenTest`（7 条，含「配了密钥时对得上才放行」
    以及「被拒时不建租户」）
- 顺带补上了 dw-lineage 的 `/internal/v1/projects/{code}` **正向链路此前零测试**的空白。

### 变难

- **多服务组合部署现在必须显式配 `MODULE_TOKEN`**，三处同一个值。不配的表现是
  「组织里建了项目，模块里看不见」并伴随 401 日志。这是刻意的取舍：
  用一个显式的失败换掉一个静默的开放。
- 三份实现仍是**副本**（三个服务不共享这些类，lineage 连 dw-common 都不依赖），
  改动需要三处同步。已在每处的 javadoc 里写明「改动需三处同步」并列出另外两处文件名；
  真正的防漂移靠的是三个服务各自的行为测试，而不是共享代码。

### 仍未解决

- **`standalone` 下 `/internal/v1/**` 仍开放**。这是既有语义（无账号体系、无组织平面），
  本次没有改动。若认为 standalone 也不该接受内部调用，可以收成 403 —— 属独立决策。
- **静态共享密钥本身的能力上限**：不能轮换、不能区分调用方、没有审计、
  泄漏后只能靠改配置且需要重启全部服务。它保护的是「内网可达性」，不是「零信任」。
  演进方向应是「模块注册时由组织下发一次性凭据（可轮换、可吊销）」或 mTLS。
  与评审 F5（三服务共用 JWT 密钥）同源：**当前这一层信任是拓扑信任，
  一旦网络边界被突破，这些密钥就是万能钥匙。**
- `/internal/v1/**` 在 SecurityConfig 里仍是 `permitAll`，门禁只在 controller 里。
  收进 Security 层（做成一条 filter）会更难绕过，但需要处理「心跳无用户身份」这个前提。
