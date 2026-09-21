# @dw-ai/engine

共享库，不是可启动产品。无用户、无租户、无 HTTP。

命名规则、分层加工、DWD/DWS 草稿、规范校验、访问判定、SQL 生成、查询聚类都在这里。  
租户管理前端、智仓前端、`dw-model/rules` 都依赖它。

## 怎么用

仓库根 `npm install` 后，workspace 会链上本包。前端 Vite 还把源码 alias 到 `packages/engine/src/index.ts`，改引擎立刻热更新。

没有独立 dev 端口。改代码后：

- 前端：保存即生效
- 规则服务：重启 `npm run dev:rules`

不要在这里写登录、租户或落库逻辑。
