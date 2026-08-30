# 构建上下文必须是仓库根目录（workspace 依赖 packages/engine）。
# docker compose / docker build -f ui/Dockerfile .
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
COPY ui ./ui
COPY packages/engine ./packages/engine
COPY services/rules ./services/rules

RUN npm ci

# `.` = 与页面同源，由 Nginx 把 /api 反代到 Java API
ARG VITE_API_BASE=.
ARG VITE_DEPLOY_MODE=multi
ENV VITE_API_BASE=$VITE_API_BASE
ENV VITE_DEPLOY_MODE=$VITE_DEPLOY_MODE
RUN npm run build -w @dw-ai/ui

FROM nginx:1.27-alpine
COPY ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/ui/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --retries=8 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
