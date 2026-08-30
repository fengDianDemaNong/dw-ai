# 产品 0.1.1

状态：已确认设计版本（当前设计版为 0.1.3）  
范围：自建账号、租户、平台后台、工作台、仓建设（规范 + 建模 AI / 版本）

## 给排期与验收

- **[RELEASE.md](./RELEASE.md)**：本版有什么 / 没有什么  
- **[spec/04-requirements.md](./spec/04-requirements.md)**：可验收需求条目  
- 实现拆分：[`docs/tech/0.1.1-checklist.md`](../../../tech/0.1.1-checklist.md)  
- 技术方案：[`docs/tech/02-0.1.1.md`](../../../tech/02-0.1.1.md)

## 书面规范

1. [spec/00-platform.md](./spec/00-platform.md)
2. [spec/01-spec-center.md](./spec/01-spec-center.md)
3. [spec/02-modeling-center.md](./spec/02-modeling-center.md)
4. [spec/03-identity-access.md](./spec/03-identity-access.md)
5. [spec/04-requirements.md](./spec/04-requirements.md)

## 交互原型

可点击全流程。启动模式（模拟服务 conf）：

- 多租户（默认）：http://127.0.0.1:4183/?mode=multi
- 普通（无平台后台、无租户）：http://127.0.0.1:4183/?mode=standard

或 `VITE_DEPLOY_MODE=standard npm run proto -- 0.1.1`。

```bash
# 在仓库根目录
npm run proto -- 0.1.1
```

浏览器：http://127.0.0.1:4183/  
顶栏有金色标签「产品原型 0.1.1」。登录页可改启动模式（会刷新，等同重启）。

本机存储键：`dw-ai.proto.0.1.1`（不会覆盖 0.1.0 的 `dw-ai.proto.0.1.0` 或实现里的 `dw-ai.state.v1`）。

演示账号见 [03-identity-access.md](./spec/03-identity-access.md)。
