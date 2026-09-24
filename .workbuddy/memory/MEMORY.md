# DW-AI 项目长期记忆

> 智仓 DW-AI 数据仓库智能建设平台。工作区: /Users/wang/Downloads/data/git-repo/dw-ai

## 项目结构

三套交付服务 + 共享库:
- `dw-org/` 租户管理/组织平台 — UI 5171 · API 18080 · 库 dw_org — 仅 multi
- `dw-model/` 智仓/仓建设(主力) — UI 5172 · API 18081 · 规则 7080 · 库 dw_mode — 三模式
- `dw-lineage/` 数据地图/血缘 — UI 5173 · API 18082(独立默认 8080) · 库 dw_lineage — pnpm 独立, 默认 standalone
- `packages/engine/` TypeScript 共享规则库(不单独启动)
- `dw-common/` Java 共享模块(抽取中)

后端 Java 21 + Spring Boot 3.3 + MyBatis-Plus 3.5;前端 Vue 3 + Vite + Ant Design Vue;
迁移 Flyway 10(h2/mysql/postgresql 三方言);库 H2(默认)/MySQL 8/PostgreSQL 16;
登录 Casdoor OIDC(可选,未配用开发 JWT)。根 pom.xml 是聚合(非继承),统一构建入口 `mvn test`。

## 端口约定(2026-09-22 更新)

lineage 前端端口已统一为 5173(multi/standard/standalone 一致,原 multi 为 5175)。
原型 `docs/product/versions/0.2.0/` 仍用 5175,属 0.2.0 设计版独立约定,待落地统一。

## 三种运行模式

standalone(无登录)/ standard(本模块本地账号)/ multi(默认,组织平台先起,签发 JWT,
仓建设与血缘复用)。开关:DW_AI_MODE(组织/仓建设)、LINEAGE_RUN_MODE(血缘)。
multi 有两层门禁:组织 JWT(Security 层) + 租户上下文请求头(TenantInterceptor)。

## 本地环境(用户实际机器,2026-09-22 确认)

- JAVA_HOME=/Users/wang/Downloads/tools/jdk-21.0.2.jdk/Contents/Home (Java 21.0.2)
- MAVEN_HOME=/Users/wang/Downloads/tools/apache-maven-3.9.16, mvn -v 正常
- 另有 JDK 17/11/9/8,按需切;编译后端统一用 JDK 21
- Node 22.13+(lineage pnpm 11.x 依赖 node:sqlite 内置模块)
- 之前"本机无 mvn/无 JDK"记录仅适用沙箱/IDE 内置场景,不代表用户实际机器
- 沙箱跑 mvn 需放行 target 写入;本机 JDK 21 不触发 IDE JBR 25 的 kapt 版本号问题,无需绕法

## 关键文档位置

- docs/tech/project-architecture.md — 架构总览(含图)
- docs/tech/pending-decisions.md — 待决策事项清单
- docs/tech/overview.md — 优化进展总览
- docs/tech/adr/ — 架构决策记录
- docs/.workbuddy/memory/ — 历史 agent 日志(注意:标准路径是仓库根 .workbuddy/memory/)
