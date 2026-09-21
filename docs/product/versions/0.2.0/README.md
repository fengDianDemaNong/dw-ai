# 产品 0.2.0

状态：当前设计版本，实现中  
范围：从 0.1.5 拉出的大版本。继续做 **独立产品组合**、**三种运行模式**、**组织与仓建设分进程**、**服务注册与租户裁模块**、**数据地图 embed**、**项目菜单三套风格与外观**。

## 给排期与验收

- **[RELEASE.md](./RELEASE.md)**：本版有什么 / 没有什么  
- **[spec/04-requirements.md](./spec/04-requirements.md)**：可验收需求条目  

交互已按当前原型确认，实现窗口按技术方案开工。

- **[PRD.md](./PRD.md)**：完整产品需求（导航、外观、数据地图、权限）
- **技术方案**：[docs/tech/07-0.2.0.md](../../../tech/07-0.2.0.md) · [清单](../../../tech/0.2.0-checklist.md)

## 书面规范

1. [spec/00-platform.md](./spec/00-platform.md)（产品 SKU、三种运行模式）
2. [spec/01-spec-center.md](./spec/01-spec-center.md)（沿用 0.1.4）
3. [spec/02-modeling-center.md](./spec/02-modeling-center.md)（沿用加工定义；可跳作业血缘）
4. [spec/03-identity-access.md](./spec/03-identity-access.md)（组织角色 / 产品角色，按模式裁）
5. [spec/04-requirements.md](./spec/04-requirements.md)
6. [spec/05-products.md](./spec/05-products.md)（单跑与组合、数据地图 embed）
7. [spec/06-runtime-modes.md](./spec/06-runtime-modes.md)（独立 / 普通 / 多租户、REST、对现有前后端的拆法）
8. [spec/07-context.md](./spec/07-context.md)（跨服务租户/项目/用户怎么传）

## 交互原型

```bash
npm run proto -- 0.2.0
```

- 组织平台（租户 / 项目 / 模块）：http://127.0.0.1:4234/
- 仓建设（规范 / 建模）：http://127.0.0.1:4233/
- 数据地图（dw-lineage，侧栏 iframe）：http://127.0.0.1:5175/

两套代码分开，单独启动。先在 4234 登录，再打开仓建设。同一 `project_id` 在各模块复用。

顶栏金色标签「产品原型 0.2.0」。存储键 `dw-ai.proto.0.2.0`，不覆盖 0.1.5 / 0.1.4 或实现窗口。

演示账号见 [03-identity-access.md](./spec/03-identity-access.md)。建议先用平台用户看服务注册（没有调度），再用张三看「计算资源」和模块管理，最后用李四 / 王五对比侧栏。
