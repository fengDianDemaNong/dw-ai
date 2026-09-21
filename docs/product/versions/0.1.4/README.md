# 产品 0.1.4

状态：交互冻结（当前设计为 0.2.0）  
范围：在 0.1.3 仓建设（身份、AI 能力、提示词、知识库、逻辑表）之上，增加 **字段加工定义**、**多源 DWS**、**影响与血缘**。

## 给排期与验收

- **[RELEASE.md](./RELEASE.md)**：本版有什么 / 没有什么  
- **[spec/04-requirements.md](./spec/04-requirements.md)**：可验收需求条目  

本版只做产品窗口。技术方案与实现清单尚未开（不要在本目录链到不存在的 `docs/tech/04-0.1.4.md`）。

## 书面规范

1. [spec/00-platform.md](./spec/00-platform.md)（SKU / AI 能力沿用 0.1.3）
2. [spec/01-spec-center.md](./spec/01-spec-center.md)（规范中心不建加工定义）
3. [spec/02-modeling-center.md](./spec/02-modeling-center.md)（加工定义、多源汇总、影响面）
4. [spec/03-identity-access.md](./spec/03-identity-access.md)（权限沿用，读模型可看血缘）
5. [spec/04-requirements.md](./spec/04-requirements.md)

## 交互原型

可点击全流程。启动模式（模拟服务 conf）：

- 多租户（默认）：http://127.0.0.1:4213/?mode=multi
- 普通（无平台后台、无租户）：http://127.0.0.1:4213/?mode=standard

或 `VITE_DEPLOY_MODE=standard npm run proto -- 0.1.4`。

```bash
# 在仓库根目录
npm run proto -- 0.1.4
```

浏览器：http://127.0.0.1:4213/  
顶栏有金色标签「产品原型 0.1.4」。登录页可改启动模式（会刷新，等同重启）。

本机存储键：`dw-ai.proto.0.1.4`（不会覆盖 0.1.3 的 `dw-ai.proto.0.1.3`、0.1.1、0.1.0 或实现里的 `dw-ai.state.v1`）。

演示账号见 [03-identity-access.md](./spec/03-identity-access.md)。
