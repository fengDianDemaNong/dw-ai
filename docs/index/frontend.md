# 前端 / TypeScript（三个 UI + 共享引擎）

> 由 `bin/gen-index.sh` 于 2026-09-23 15:45:01 生成（HEAD `194259f`）。**不要手工编辑**，改完代码重跑脚本即可。
> **路径 = 小节标题里的模块名 + `/ui/src/`（`engine` 片段为 `packages/engine/src/`） + 下表路径**。路由表在各 UI 的 `ui/src/router/` 下，需要完整 path → 组件映射时直接读那个文件。


## dw-org

源码根 `dw-org/ui/src`，共 54 个文件。

### dw-org · router（路由）

- `router/index.ts`

### dw-org · pages（页面）

- `pages/admin/services.vue`
- `pages/admin/tenants.vue`
- `pages/admin/users.vue`
- `pages/auth-callback.vue`
- `pages/forbidden.vue`
- `pages/login.vue`
- `pages/no-project.vue`
- `pages/org-users.vue`
- `pages/projects.vue`
- `pages/select-tenant.vue`
- `pages/sys/knowledge.vue`
- `pages/sys/roles.vue`
- `pages/sys/settings.vue`

### dw-org · layouts（布局）

- `layouts/SystemLayout.vue`

### dw-org · components（组件）

- `components/AppNav.vue`
- `components/AppSidebar.vue`
- `components/AppearancePickers.vue`
- `components/KnowledgeManual.vue`
- `components/PageHeader.vue`
- `components/ProjectSwitcher.vue`
- `components/SqlBlock.vue`
- `components/UserPanel.vue`

### dw-org · api（API 封装）

- `api/client.ts`

### dw-org · stores（状态）

- `stores/app.ts`
- `stores/prefs.ts`

### dw-org · engine（引擎）

- `engine/access.ts`
- `engine/knowledgeIo.ts`
- `engine/materialize.ts`
- `engine/metrics.ts`
- `engine/modeling.ts`
- `engine/naming.ts`
- `engine/specChat.ts`
- `engine/specIo.ts`

### dw-org · config（配置）

- `config/aiPrompts.ts`
- `config/grades.ts`
- `config/iam.ts` — 权限矩阵（`ROLE_PERMS`）与判权函数（`roleHas`）在共享包里 —— 工作台、仓建设、 数据地图三个前端都要用它画菜单，各抄一份的话加一个产品得改三处，而漏掉的那一处 不会编译报错，只在运行时判否。
- `config/knowledge.ts`
- `config/layerPolicies.ts`
- `config/layers.ts`
- `config/nav.ts`
- `config/navIcons.ts`
- `config/pages.ts` — 组织平台只做租户管理，只有 multi。
- `config/paths.ts`
- `config/product.ts`
- `config/runtime.ts`
- `config/sysNav.ts`
- `config/version.ts`

### dw-org · auth（鉴权）

- `auth/casdoor.ts`

### dw-org · types（类型）

- `types/index.ts`

### dw-org · mock（模拟数据）

- `mock/seed.ts`

### dw-org · (顶层)

- `App.vue`
- `main.ts`
- `vite-env.d.ts` — / <reference types="vite/client" />

## dw-model

源码根 `dw-model/ui/src`，共 89 个文件。

### dw-model · router（路由）

- `router/index.ts`

### dw-model · pages（页面）

- `pages/admin/tenants.vue`
- `pages/admin/users.vue`
- `pages/auth-callback.vue`
- `pages/dashboard.vue`
- `pages/dev/jobs.vue`
- `pages/dev/lineage.vue`
- `pages/dev/quality.vue`
- `pages/forbidden.vue`
- `pages/knowledge.vue`
- `pages/login.vue`
- `pages/map/embed.vue`
- `pages/materialize.vue`
- `pages/members.vue`
- `pages/model/copilot.vue`
- `pages/model/dwd-detail.vue`
- `pages/model/dwd-dws.vue`
- `pages/model/dwd-overview.vue`
- `pages/model/dwd-tables.vue`
- `pages/model/ods-dwd.vue`
- `pages/model/validate.vue`
- `pages/model/versions.vue`
- `pages/no-project.vue`
- `pages/org-users.vue`
- `pages/projects.vue`
- `pages/select-tenant.vue`
- `pages/serve/factory.vue`
- `pages/serve/market.vue`
- `pages/service/catalog.vue`
- `pages/service/factory.vue`
- `pages/service/gateway.vue`
- `pages/service/market.vue`
- `pages/spec/copilot.vue`
- `pages/spec/domains.vue`
- `pages/spec/grades.vue`
- `pages/spec/io.vue`
- `pages/spec/layers.vue`
- `pages/spec/logic.vue`
- `pages/spec/roots.vue`
- `pages/sys/knowledge.vue`
- `pages/sys/roles.vue`
- `pages/sys/settings.vue`

### dw-model · layouts（布局）

- `layouts/ProjectLayout.vue`
- `layouts/SystemLayout.vue`

### dw-model · components（组件）

- `components/AppNav.vue`
- `components/AppSidebar.vue`
- `components/DdlPreview.vue`
- `components/GradeTag.vue`
- `components/KnowledgeManual.vue`
- `components/PageHeader.vue`
- `components/ProductEmbed.vue`
- `components/ProjectSwitcher.vue`
- `components/SpecReadonlyTip.vue`
- `components/SqlBlock.vue`
- `components/TableFormModal.vue`
- `components/TableLineage.vue`
- `components/UserPanel.vue`
- `components/confirmImpact.ts`

### dw-model · api（API 封装）

- `api/client.ts`

### dw-model · stores（状态）

- `stores/app.ts`
- `stores/prefs.ts`

### dw-model · engine（引擎）

- `engine/access.ts`
- `engine/knowledgeIo.ts`
- `engine/materialize.ts`
- `engine/metrics.ts`
- `engine/modeling.ts`
- `engine/naming.ts`
- `engine/specChat.ts`
- `engine/specIo.ts`

### dw-model · config（配置）

- `config/aiPrompts.ts`
- `config/grades.ts`
- `config/iam.ts` — 权限矩阵（`ROLE_PERMS`）与判权函数（`roleHas`）在共享包里 —— 工作台、仓建设、 数据地图三个前端都要用它画菜单，各抄一份的话加一个产品得改三处，而漏掉的那一处 不会编译报错，只在运行时判否。
- `config/knowledge.ts`
- `config/layerPolicies.ts`
- `config/layers.ts`
- `config/nav.ts` — 进这一页需要本项目下的哪个权限（见 `@dw-ai/engine` 的 `ROLE_PERMS`）。
- `config/navIcons.ts`
- `config/pages.ts` — 三个产品的页面归属。
- `config/paths.ts`
- `config/product.ts`
- `config/runtime.ts` — ?mode= 只在开发态生效（规格 06-runtime-modes：不要靠前端 ?mode= 当生产开关）。
- `config/sysNav.ts` — 工作台菜单。
- `config/version.ts`

### dw-model · auth（鉴权）

- `auth/casdoor.ts`

### dw-model · types（类型）

- `types/index.ts`

### dw-model · mock（模拟数据）

- `mock/seed.ts`

### dw-model · (顶层)

- `App.vue`
- `main.ts`
- `vite-env.d.ts` — / <reference types="vite/client" />

## dw-lineage

源码根 `dw-lineage/ui/src`，共 74 个文件。

### dw-lineage · router（路由）

- `router/index.ts`

### dw-lineage · pages（页面）

- `pages/login.vue`
- `pages/map/analyze.vue`
- `pages/map/catalogs.vue`
- `pages/map/lineage-detail.vue`
- `pages/map/lineage-list.vue`
- `pages/map/temp-rules.vue`
- `pages/meta.vue`
- `pages/overview.vue`
- `pages/search.vue`
- `pages/settings/map.vue`
- `pages/settings/metadata-sources.vue`
- `pages/settings/preferences.vue`
- `pages/settings/projects.vue`
- `pages/settings/tenants.vue`
- `pages/settings/users.vue`

### dw-lineage · layouts（布局）

- `layouts/AppLayout.vue`

### dw-lineage · components（组件）

- `components/AppSidebar/index.vue`
- `components/AppTopbar/index.vue`
- `components/AppTopnav/index.vue`
- `components/Header/headerButton.vue`
- `components/Header/index.vue`
- `components/Icon/IconClear.vue`
- `components/Icon/IconCopy.vue`
- `components/Icon/IconFormat.vue`
- `components/Icon/IconRedo.vue`
- `components/Icon/IconSearch.vue` — 搜索图标组件
- `components/Icon/IconUndo.vue`
- `components/Icon/IconWrap.vue`
- `components/LineageGraph/components/Layout/CustomDagreLayout.ts` — 默认从左到右（maxLayer--->minLayer) 默认居中对齐
- `components/LineageGraph/components/Layout/index.ts`
- `components/LineageGraph/components/Toolbar/index.vue`
- `components/LineageGraph/components/Topbar/index.vue`
- `components/LineageGraph/index.vue`
- `components/LineageGraph/registerLayout.ts` — G6.registerLayout('lineageLayout', { /** * 定义自定义行为的默认参数，会与用户传入的参数进行合并 */ getDefaultCfg() { return {}; }, /** * 初始化 * @param {Object} data 数据 */ init(data: any) …
- `components/LineageGraph/registerShape.ts` — 行高
- `components/LineageGraphTest/index.vue`
- `components/MiniTrend/index.vue`
- `components/MonacoEditor/index.vue`
- `components/MonacoEditor/types.ts` — Width of editor.
- `components/MonacoEditor/utils.ts`
- `components/PageHeader/index.vue`
- `components/ProjectScopeSwitcher/index.vue`
- `components/ProjectSwitcher/index.vue`
- `components/RatioBar/index.vue`
- `components/RemoteMetaBrowser/index.vue`
- `components/StatTile/index.vue`
- `components/Tour/index.vue`
- `components/UserMenu/index.vue`

### dw-lineage · services（服务）

- `services/api.ts`
- `services/statsMock.ts` — 概览页的开发期假数据。

### dw-lineage · stores（状态）

- `stores/auth.ts` — standard（普通模式）的本地会话：登录、令牌、续期、登出。
- `stores/catalog.ts` — 当前项目的默认数据目录名。
- `stores/preferences.ts` — 界面偏好。
- `stores/tenant.ts` — 当前租户与项目。
- `stores/ui.ts` — 内容区实际宽度。

### dw-lineage · config（配置）

- `config/api.ts` — 后端基址。
- `config/embed.ts`
- `config/iam.ts` — 本进程的产品码。
- `config/nav.ts` — 导航结构。
- `config/navIcons.ts` — 菜单图标表。
- `config/pages.ts` — 本地登录页。
- `config/runtime.ts` — ?mode= 只在开发态生效（规格 06-runtime-modes：不要靠前端 ?mode= 当生产开关）。
- `config/tableTypes.ts` — 数仓表类型。

### dw-lineage · utils（工具）

- `utils/common.ts` — 创建表名到最高层级的映射
- `utils/graphUtil.ts` — 放大 @param graph
- `utils/request.ts`

### dw-lineage · types（类型）

- `types/index.ts`

### dw-lineage · shims（垫片）

- `shims/antv-algorithm-async.ts` — @antv/g6-pc@0.8.18 静态 import 了 "@antv/algorithm/lib/asyncIndex"， 但该文件在 @antv/algorithm 的所有已发布版本中都不存在（上游打包缺陷）， 导致 vite 构建报 "Rollup failed to resolve import"。

### dw-lineage · test（测试）

- `test/sql.ts`
- `test/test.ts`

### dw-lineage · (顶层)

- `App.vue` — 应用外壳。
- `main.ts` — 按需导入 ant-design-vue 组件
- `vite-env.d.ts` — / <reference types="vite/client" />

## engine

源码根 `packages/engine/src`，共 20 个文件。

### engine · config（配置）

- `config/grades.ts`
- `config/layerPolicies.ts`

### engine · (顶层)

- `access.ts`
- `aiPrompts.ts`
- `ddl.ts`
- `fieldLogic.ts`
- `iam.ts` — 产品码，与产品 SKU 对应。
- `impact.ts`
- `index.ts`
- `knowledge.ts`
- `knowledgeIo.ts`
- `materialize.ts`
- `metrics.ts`
- `modelChat.ts`
- `modelVersion.ts`
- `modeling.ts`
- `naming.ts`
- `specChat.ts`
- `specIo.ts`
- `types.ts`

