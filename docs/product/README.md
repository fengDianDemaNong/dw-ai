# 产品设计

产品真源按**版本目录**存放。每个版本包含：书面规范 + **可点击的完整交互原型**。

当前发布版本：**0.1.3**（安装包与实现窗口；AI 能力、提示词、知识库）  
当前设计版本：**0.2.0**（大版本：模块三模式、组织与仓建设分进程、服务注册与租户裁模块）  
上一设计版本：**0.1.5**（同上范围的上一稿，不再改结论）

| 版本 | 说明 | 交互原型 |
|---|---|---|
| [0.1.0](./versions/0.1.0/) | 规范中心 + 建模中心（仓建设，已冻结） | `npm run proto -- 0.1.0` → http://127.0.0.1:4173/ |
| [0.1.1](./versions/0.1.1/) | 自建账号、租户、平台后台、工作台、建模 AI 与版本 | `npm run proto -- 0.1.1` → http://127.0.0.1:4183/ · [Release 清单](./versions/0.1.1/RELEASE.md) |
| [0.1.2](./versions/0.1.2/) | 多库、手动 seed、依赖与业务 jar 分离 | 无新原型 · [Release](./versions/0.1.2/RELEASE.md) · [产品手册](../user/产品手册.md) · [使用说明](../user/使用说明.md) |
| [0.1.3](./versions/0.1.3/) | AI 能力项、工作台提示词、规范/建模对话带项目上下文、知识库（已实现，原型冻结） | `npm run proto -- 0.1.3` → http://127.0.0.1:4203/ · [Release](./versions/0.1.3/RELEASE.md) |
| [0.1.4](./versions/0.1.4/) | 字段加工、多源汇总、影响与血缘（交互冻结） | `npm run proto -- 0.1.4` → http://127.0.0.1:4213/ · [Release](./versions/0.1.4/RELEASE.md) |
| [0.1.5](./versions/0.1.5/) | 每模块独立/普通/多租户；组织与仓建设分进程（上一设计稿） | `npm run proto -- 0.1.5` → 组织 4224 / 仓建设 4223 · [Release](./versions/0.1.5/RELEASE.md) |
| [0.2.0](./versions/0.2.0/) | 大版本调整：三模式、分进程、服务注册、数据地图、菜单三套风格 | `npm run proto` → 组织 4234 / 仓建设 4233 / 数据地图 5175 · [PRD](./versions/0.2.0/PRD.md) · [技术方案](../tech/07-0.2.0.md) · [Release](./versions/0.2.0/RELEASE.md) |

实现代码：**租户管理** `dw-org/ui` + `dw-org/api`（5174 / 18080，库 `dw_org`）；**智仓** `dw-model/ui` + `dw-model/api`（5173 / 18081，库 `dw_mode`）。原型在 `docs/product/versions/`，端口 4234 / 4233，两套互不影响。0.1.5 产品已冻结交互。0.2.0 实现见 [`docs/tech/07-0.2.0.md`](../tech/07-0.2.0.md)。

## 两个窗口怎么开

1. **产品窗口**：Cursor 打开本仓库，只改 `docs/product/versions/`。跑 `npm run proto` 打开当前设计版 **0.2.0（组织 4234 / 仓建设 4233 / 数据地图 5175）**。4173 是冻结的 0.1.0；4183 是 0.1.1；4203 是冻结的 0.1.3；4213 是冻结的 0.1.4；4224 / 4223 是 0.1.5。
2. **实现窗口**：再开一个 Cursor 窗口，同样打开本仓库，只改 `dw-org/`、`dw-model/`、`dw-lineage/`、`packages/engine`。租户管理 `npm run dev:org` + `npm run dev:api:org`；智仓 `npm run dev:model` + `npm run dev:api:model`。

不要在实现窗口里改 `docs/product/versions/` 里的原型结论；不要在产品窗口里改 `dw-org/`、`dw-model/` 当正式功能。

开下一版设计：

```bash
./docs/product/new-version.sh 0.2.0 0.2.1
npm run proto -- 0.2.1
```

会复制源版本的文档和整份可交互原型到新目录，换存储键和端口，避免和已有版本 / 实现抢数据。复制后请确认 `vite.config.ts` 端口未与已有版本冲突（脚本按 minor 估算，组织 / 仓建设双端口需手改）。

## 纪律

- 产品结论只写在对应版本的 `spec/`，不写框架版本。
- 技术方案见 `docs/tech/`，只能引用某版本 `spec/`，不能反向改产品。
- 0.1.0 / 0.1.1 / 0.1.3 / 0.1.4 / 0.1.5 冻结后，新想法进 0.2.0，不要改已冻结原型行为（除非修原型自身的阻断 bug）。
