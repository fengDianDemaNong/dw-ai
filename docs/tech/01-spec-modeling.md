# 规范 / 建模 API

组织（Casdoor）= 租户；项目成员与角色在智仓表 `project_members`。

## 鉴权

- `SECURITY_MODE=dev`：`POST /api/auth/login` 签发 HS256 JWT（仅开发）。
- `SECURITY_MODE=oidc`：Resource Server 用 Casdoor JWKS 校验 RS256。
- 上下文：JWT `tenant_id` 或 Casdoor `owner`（经 `CASDOOR_ORG_MAP` / `tenants.code`）+ `X-Project-Id`。

## 资源（均挂 `/api/projects/{projectId}`）

| 方法 | 路径 | 权限 |
|---|---|---|
| GET/POST | `/domains` | spec:read / spec:write |
| PUT/DELETE | `/domains/{id}` | spec:write |
| GET/POST | `/layers` | 同上 |
| PUT/DELETE | `/layers/{layer}` | spec:write |
| GET/POST | `/grades` | 同上 |
| PUT/DELETE | `/grades/{id}` | spec:write |
| GET/POST | `/roots` | 同上 |
| PUT/DELETE | `/roots/{id}` | spec:write |
| PUT | `/spec` | 整包同步规范 |
| GET/POST | `/tables` | model:read / model:write |
| PUT/DELETE | `/tables/{id}` | model:write |
| GET/POST | `/drafts` | model:read / model:write |
| GET | `/snapshot` | 项目规范+表+草案一次拉齐 |
| GET/PUT/DELETE | `/members` | spec:read / iam:member |

角色码与演示一致：admin / modeler / viewer。删除主题域/分层/等级时，仍被表引用则 409。
