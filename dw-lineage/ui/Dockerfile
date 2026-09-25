# 构建上下文必须是仓库根目录：前端依赖 npm workspace 的 packages/engine，
# 而 npm ci 又会按根 package.json 的 workspaces 数组去解析全部五个成员目录，
# 少拷任何一个都会让 ci 失败（它们互为同级的 workspace，没有主次）。
# 见 docs/tech/adr/0014-frontend-package-manager-unification.md。
#   docker build -f dw-lineage/ui/Dockerfile .
# 或：cd dw-lineage && docker compose build frontend
#
# Node 版本必须 ≥ 22.13（见 package.json 的 engines）。这个下限是 pnpm 时期定的
# （pnpm 11.x 依赖 node:sqlite），迁到 npm 后已非必需，但保留不动 —— node 版本是
# 独立变量，与包管理器迁移混在一起会让「构建挂了」难以归因。改这里请同步 engines。
FROM node:22-alpine AS builder

WORKDIR /app

# 先只拷依赖描述，最大化利用层缓存。
COPY package.json package-lock.json ./
COPY dw-org/ui ./dw-org/ui
COPY packages/engine ./packages/engine
COPY dw-model/ui ./dw-model/ui
COPY dw-model/rules ./dw-model/rules
COPY dw-lineage/ui ./dw-lineage/ui

RUN npm ci

# 与 package.sh / ci.sh 同口径：build:no-check 是纯 vite build（不含 vue-tsc 类型检查），
# 且 vite.config.ts 的 base 是 VITE_PUBLIC_PATH || '/' —— 本镜像不设该变量，即 '/'，
# 与安装包把前端挂在根路径的约定一致。
RUN npm run build:no-check -w sql-tools

# ---------- 运行阶段 ----------
FROM nginx:stable-alpine

# 后端地址，由 nginx 官方 entrypoint 的 envsubst 注入 default.conf.template
ENV BACKEND_HOST=backend
ENV BACKEND_PORT=18082

# 挂根路径，见 nginx.conf 的说明。产物里的资源是 /assets/...，与 nginx 的 location / 对齐。
# 不要给这里加 VITE_PUBLIC_PATH=/lineage/ —— base 与路由前缀会叠成 /lineage/lineage/...。
COPY --from=builder /app/dw-lineage/ui/dist /usr/share/nginx/html

# 放到 templates 下，容器启动时会自动做变量替换并输出到 /etc/nginx/conf.d/
COPY dw-lineage/ui/nginx.conf /etc/nginx/templates/default.conf.template

# ---- 运行时配置 ----
# 容器启动时用环境变量渲染 /usr/share/nginx/html/config.json，
# 于是「前端和后端不在同一台服务器」这种只有部署期才知道的事，
# 改一个环境变量重启容器即可，不必重新构建镜像。
# 模板放 /etc/nginx 下而不是 html 里，避免它被当成静态资源对外暴露。
# 见 config.json.template 与 docker-entrypoint.d/25-app-config.sh 内的说明。
COPY dw-lineage/ui/config.json.template /etc/nginx/config.json.template
COPY dw-lineage/ui/docker-entrypoint.d/25-app-config.sh /docker-entrypoint.d/25-app-config.sh
RUN chmod +x /docker-entrypoint.d/25-app-config.sh

EXPOSE 80

# 不要覆盖 ENTRYPOINT，否则官方 entrypoint 的 envsubst 不会执行
