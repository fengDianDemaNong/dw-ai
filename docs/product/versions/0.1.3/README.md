# 产品 0.1.3

状态：当前设计版本  
范围：在 0.1.1 身份 / 三套界面 / 仓建设之上，增加 **AI 能力开通**、**工作台提示词**、**规范/建模对话带本项目规范**

## 给排期与验收

- **[RELEASE.md](./RELEASE.md)**：本版有什么 / 没有什么  
- **[spec/04-requirements.md](./spec/04-requirements.md)**：可验收需求条目  
- 实现拆分：[`docs/tech/0.1.3-checklist.md`](../../../tech/0.1.3-checklist.md)  
- 技术方案：[`docs/tech/04-0.1.3.md`](../../../tech/04-0.1.3.md)

## 书面规范

1. [spec/00-platform.md](./spec/00-platform.md)（开通 AI 能力）
2. [spec/01-spec-center.md](./spec/01-spec-center.md)（规范设计 / 问答、项目上下文）
3. [spec/02-modeling-center.md](./spec/02-modeling-center.md)（建模 AI 带规范）
4. [spec/03-identity-access.md](./spec/03-identity-access.md)（授权码 ∩ 能力）
5. [spec/04-requirements.md](./spec/04-requirements.md)

## 交互原型

可点击全流程。启动模式（模拟服务 conf）：

- 多租户（默认）：http://127.0.0.1:4203/?mode=multi
- 普通（无平台后台、无租户）：http://127.0.0.1:4203/?mode=standard

或 `VITE_DEPLOY_MODE=standard npm run proto -- 0.1.3`。

```bash
# 在仓库根目录
npm run proto -- 0.1.3
```

浏览器：http://127.0.0.1:4203/  
顶栏有金色标签「产品原型 0.1.3」。登录页可改启动模式（会刷新，等同重启）。

本机存储键：`dw-ai.proto.0.1.3`（不会覆盖 0.1.1 的 `dw-ai.proto.0.1.1`、0.1.0 的 `dw-ai.proto.0.1.0` 或实现里的 `dw-ai.state.v1`）。

演示账号见 [03-identity-access.md](./spec/03-identity-access.md)。
