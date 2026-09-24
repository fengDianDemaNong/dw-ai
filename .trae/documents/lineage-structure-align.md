# lineage 项目结构对齐 org/model 模块

## Context

仓库根 README 约定：每个已交付服务同一套目录约定 —— 前端 `ui/`、后端 `api/`、安装脚本 `packaging/`，统一用 `package.sh` 作打包入口、根级 `package.json` 提供 npm 脚本。

`dw-org` 和 `dw-model` 已遵循此约定。`dw-lineage` 的前后端目录已对齐（`api/` + `ui/`），但打包资源塞在 `release/bin`+`release/conf`（而非独立 `packaging/`），打包入口是 `build-release.sh`（无 `package.sh`），缺根级 `package.json`，根目录还堆了一批历史阶段文档。本任务把 lineage 的打包结构与脚本入口对齐到 org/model 约定，不动前后端源码与 docker-compose 的 build context。

预期结果：`dw-lineage/` 顶层布局与 org/model 一致 —— 有 `api/` `ui/` `packaging/` `package.json` `package.sh` `docker-compose.yml` `release/`（产物输出）`docs/`，根目录无散落历史文档。

## 改动清单

### 1. 迁移打包资源到 packaging/

把 `dw-lineage/release/bin` 和 `dw-lineage/release/conf` 整体迁移为 `dw-lineage/packaging/bin` 和 `dw-lineage/packaging/conf`（用 `git mv` 保留历史）。

- `release/bin/` 现有 8 个脚本（common.sh / init-db.sh / restart.sh / seed-demo.sh / sql-cli.sh / start.sh / status.sh / stop.sh）全迁。
- `release/conf/` 现有 `application.yml` + `env.sh` 全迁。
- 迁移后 `release/` 只保留 `sql/`、`INSTALL.md`，恢复为纯产物输出目录（与 org/model 一致）。
- `release/bin` 里脚本内部若有 `release/` 相对引用，需改为 `packaging/`（检查 `common.sh` / `start.sh`）。

### 2. 新建 dw-lineage/package.json

仿 [dw-org/package.json](file:///Users/wang/Downloads/data/git-repo/dw-ai/dw-org/package.json)，但 ui 用 pnpm（lineage 前端一直是 pnpm，不进 npm workspaces，见根 package.json 注释）。

```json
{
  "name": "dw-lineage",
  "private": true,
  "version": "1.0.0",
  "type": "module",
  "description": "数据地图 / 血缘分析",
  "scripts": {
    "dev": "pnpm --prefix ui run dev:standard",
    "dev:lineage": "pnpm --prefix ui run dev:standard",
    "dev:api": "mvn -f api/pom.xml spring-boot:run",
    "dev:api:lineage": "mvn -f api/pom.xml spring-boot:run",
    "build": "pnpm --prefix ui run build",
    "package": "bash package.sh"
  }
}
```

版本号 `1.0.0` 沿用 `build-release.sh` 原本的 `VERSION="${VERSION:-1.0.0}"`，不沿用 org/model 的 0.1.3（lineage 已发过 1.0.0 包）。

### 3. 新建 dw-lineage/package.sh

仿 [dw-org/package.sh](file:///Users/wang/Downloads/data/git-repo/dw-ai/dw-org/package.sh) 结构，适配 lineage：

- `MODE` 支持 `all|api|ui`，与 org/model 一致。
- 后端构建：`mvn -B -DskipTests -q -f api/pom.xml clean package`，jar 名匹配 `api/target/sql-tools-*.jar`（`grep -v sources`）。
- 前端构建：`pnpm --prefix ui install --no-frozen-lockfile` + `npx --no-install vite build --base=/`（沿用 build-release.sh 的 base=/，后端 WebStaticConfig 依赖根路径）。
- `packaging/bin` `packaging/conf` 从 `dw-lineage/packaging/` 取（迁移后路径）。
- SQL 派生：沿用 build-release.sh 的逻辑 —— 从 `api/src/main/resources/db/migration/{h2,mysql,postgresql}/V*__*.sql` 派生 `01_schema.sql`/`02_init_data.sql` 到产物 `sql/<db>/`，不在 release/sql 维护第二份。
- 产物输出到 `release/dw-lineage-<version>/`（与 org/model 的 `release/<name>/` 一致），不再用 `dist/`。
- `patch_env` 改 `APP_JAR_NAME`（lineage env.sh 若无此变量则跳过该步）。
- 不需要 dw-common 依赖（lineage api pom 独立，不像 org/model 引用 dw-common）。

### 4. 删除 build-release.sh

`build-release.sh` 的功能完全被 `package.sh` 取代，删除。`ci.sh` 保留（有测试下限守卫，用户已确认保留）。

### 5. 归档历史文档到 docs/archive/

把以下根目录文档 `git mv` 到 `dw-lineage/docs/archive/`：

- `PHASE1_REPORT.md`
- `PHASE2_PLAN.md` `PHASE2_REPORT.md`
- `PHASE3_BACKLOG.md` `PHASE3_PLAN.md` `PHASE3_PROGRESS.md`
- `OPTIMIZATION_PLAN.md`

`docs/` 现有 `adr/` `ARCHITECTURE.md` 等保留原位。新建 `docs/archive/` 子目录收纳。

### 6. 修正根 package.json 的 lineage 脚本

[package.json](file:///Users/wang/Downloads/data/git-repo/dw-ai/package.json) L20-L21 现状：

```json
"dev:lineage": "npm --prefix dw-lineage/ui run dev:multi",
"dev:api:lineage": "mvn -f dw-lineage/api/pom.xml spring-boot:run -Dspring-boot.run.profiles=multi",
```

改为（与"默认 standard 模式"一致）：

```json
"dev:lineage": "npm --prefix dw-lineage/ui run dev:standard",
"dev:api:lineage": "mvn -f dw-lineage/api/pom.xml spring-boot:run",
```

并在 `package:*` 区段补：

```json
"package:lineage": "bash dw-lineage/package.sh",
"package:lineage:api": "bash dw-lineage/package.sh api",
"package:lineage:ui": "bash dw-lineage/package.sh ui",
```

### 7. 清理 dist/（可选）

`dw-lineage/dist/` 现存旧构建产物（`sql-lineage-1.0.0/` 等）。产物输出改到 `release/` 后，`dist/` 整个删除（git 忽略或删目录）。若 `.gitignore` 未覆盖 `dist/`，补一行。

### 不改动项

- `api/` `ui/` 源码不动。
- `docker-compose.yml` 的 build context 已是 `./api` `./ui`，不动；镜像名 `sql-tools-backend/frontend` 保留（改名破坏性大且非结构对齐核心）。
- `ui/package.json` 的 `name: "sql-tools"` 保留（lineage 不进 npm workspaces，name 不影响解析）。
- `ci.sh` 保留，其引用的 `ROOT/api` `ROOT/ui` 路径无需改。

## 验证

1. 结构对齐检查：
   ```bash
   ls dw-lineage/  # 应见 api/ ui/ packaging/ docs/ release/ package.json package.sh ci.sh docker-compose.yml
   ls dw-lineage/packaging/bin  # 8 个脚本
   ls dw-lineage/packaging/conf  # application.yml env.sh
   ```
2. packaging/bin 脚本内部引用：`grep -rn 'release/bin\|release/conf' dw-lineage/packaging/` 应无残留（有则改为 packaging/）。
3. 打包冒烟：
   ```bash
   cd dw-lineage && bash package.sh ui   # 前端包
   ls release/  # 应有 dw-lineage-1.0.0-ui.tar.gz
   ```
4. dev 脚本：
   ```bash
   npm run dev:lineage      # 应起 standard 模式前端 5173
   npm run dev:api:lineage  # 应起后端，无 -Dspring-boot.run.profiles=multi
   ```
5. 根 package.json 脚本完整：
   ```bash
   npm run package:lineage --dry-run  # 脚本能解析
   ```
