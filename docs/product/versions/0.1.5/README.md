# 产品 0.1.5

状态：上一设计版本（大改进入 0.2.0，本目录不再改结论）  
范围：在 0.1.4 仓建设之上，演示 **独立产品组合**、**平台服务注册**、**租户内按权限裁模块**、**分产品角色**。

## 给排期与验收

- **[RELEASE.md](./RELEASE.md)**：本版有什么 / 没有什么  
- **[spec/04-requirements.md](./spec/04-requirements.md)**：可验收需求条目  

本版只做产品窗口。确认交互后再开实现，不要在本目录链到不存在的技术文档。

## 书面规范

1. [spec/00-platform.md](./spec/00-platform.md)（产品 SKU、三种运行模式）
2. [spec/01-spec-center.md](./spec/01-spec-center.md)（沿用 0.1.4）
3. [spec/02-modeling-center.md](./spec/02-modeling-center.md)（沿用加工定义；可跳作业血缘）
4. [spec/03-identity-access.md](./spec/03-identity-access.md)（组织角色 / 产品角色，按模式裁）
5. [spec/04-requirements.md](./spec/04-requirements.md)
6. [spec/05-products.md](./spec/05-products.md)（单跑与组合、元数据 embed）
7. [spec/06-runtime-modes.md](./spec/06-runtime-modes.md)（独立 / 普通 / 多租户、REST、对现有前后端的拆法）

## 交互原型

```bash
npm run proto -- 0.1.5
```

- 组织平台（租户 / 项目 / 模块）：http://127.0.0.1:4224/
- 仓建设（规范 / 建模）：http://127.0.0.1:4223/

两套代码分开，单独启动。先在 4224 登录，再打开仓建设。同一 `project_id` 在各模块复用。

顶栏金色标签「产品原型 0.1.5」。存储键 `dw-ai.proto.0.1.5`，不覆盖 0.1.4 或实现窗口。

演示账号见 [03-identity-access.md](./spec/03-identity-access.md)。建议先用平台用户看服务注册（没有调度），再用张三看「计算资源」和模块管理，最后用李四 / 王五对比侧栏。
