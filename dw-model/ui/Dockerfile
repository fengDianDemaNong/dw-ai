# 构建上下文必须是仓库根目录（workspace 依赖 packages/engine）。
# docker compose / docker build -f dw-model/ui/Dockerfile .
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
COPY dw-org/ui ./dw-org/ui
COPY packages/engine ./packages/engine
COPY dw-model/ui ./dw-model/ui
COPY dw-model/rules ./dw-model/rules
COPY dw-lineage/ui ./dw-lineage/ui

RUN npm ci

# `.` = 与页面同源，由 Nginx 把 /api 反代到 Java API
ARG VITE_API_BASE_URL=.
ARG VITE_DEPLOY_MODE=multi
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_DEPLOY_MODE=$VITE_DEPLOY_MODE
RUN npm run build -w @dw-ai/ui

FROM nginx:1.27-alpine
COPY dw-model/ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dw-model/ui/dist /usr/share/nginx/html

# ---- 运行时配置 ----
# 容器启动时用环境变量渲染 /usr/share/nginx/html/config.json，
# 于是「前端和后端不在同一台服务器」这种只有部署期才知道的事，
# 改一个环境变量重启容器即可，不必重新构建镜像。
# 模板放 /etc/nginx 下而不是 html 里，避免它被当成静态资源对外暴露。
# 见 config.json.template 与 docker-entrypoint.d/25-app-config.sh 内的说明。
COPY dw-model/ui/config.json.template /etc/nginx/config.json.template
COPY dw-model/ui/docker-entrypoint.d/25-app-config.sh /docker-entrypoint.d/25-app-config.sh
RUN chmod +x /docker-entrypoint.d/25-app-config.sh

EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --retries=8 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
