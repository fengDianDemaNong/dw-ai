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
ARG VITE_API_BASE=.
ARG VITE_DEPLOY_MODE=multi
ENV VITE_API_BASE=$VITE_API_BASE
ENV VITE_DEPLOY_MODE=$VITE_DEPLOY_MODE
RUN npm run build -w @dw-ai/ui

FROM nginx:1.27-alpine
COPY dw-model/ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dw-model/ui/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --retries=8 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
