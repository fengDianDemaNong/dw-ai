# ---------- 构建阶段 ----------
# Node 版本必须 ≥ 22.13：package.json 的 packageManager 指定了 pnpm 11.x，
# 它内部 require('node:sqlite')，而该内置模块 22.13 才有。
# 用 node:20 会在 pnpm install 阶段直接崩在 ERR_UNKNOWN_BUILTIN_MODULE。
# 改动此处时请同步 package.json 的 engines 字段。
FROM node:22-alpine AS builder

ENV PNPM_HOME="/pnpm"
ENV PATH="$PNPM_HOME:$PATH"

WORKDIR /app

# 先只拷依赖描述，最大化利用层缓存。
# pnpm-workspace.yaml 必须一并拷入：allowBuilds 配置在其中，
# 缺了它 pnpm 会因为拒绝执行 esbuild 的 install 脚本而中断。
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./

# pnpm 版本由 package.json 的 packageManager 字段决定，不写死也不用 latest
RUN corepack enable && corepack install

RUN --mount=type=cache,id=pnpm,target=/pnpm/store pnpm install --frozen-lockfile

COPY . .
RUN pnpm run build:no-check

# ---------- 运行阶段 ----------
FROM nginx:stable-alpine

# 后端地址，由 nginx 官方 entrypoint 的 envsubst 注入 default.conf.template
ENV BACKEND_HOST=backend
ENV BACKEND_PORT=8080

COPY --from=builder /app/dist /usr/share/nginx/html/sql-tools

# 删掉 nginx 自带的欢迎页：应用在 /sql-tools/ 下，这张页面留着只会在
# 访问其它路径时冒出来，让人误以为部署到了一个空 nginx 上
RUN rm -f /usr/share/nginx/html/index.html

# 放到 templates 下，容器启动时会自动做变量替换并输出到 /etc/nginx/conf.d/
COPY nginx.conf /etc/nginx/templates/default.conf.template

EXPOSE 80

# 不要覆盖 ENTRYPOINT，否则官方 entrypoint 的 envsubst 不会执行
