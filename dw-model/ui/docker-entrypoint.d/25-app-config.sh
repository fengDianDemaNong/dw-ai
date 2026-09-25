#!/bin/sh
# 容器启动时用环境变量渲染 /usr/share/nginx/html/config.json。
#
# <h2>为什么需要自己一个脚本</h2>
# nginx 官方 entrypoint 会按文件名顺序执行 /docker-entrypoint.d/ 下的 *.sh，
# 但其中的 20-envsubst-on-templates.sh 只处理 /etc/nginx/templates/*.template → conf.d，
# 不碰 html 目录。而运行时配置必须落在站点根 /config.json，所以得自己补一个。
#
# 编号 25 排在 20 之后、30 之前 —— 顺序上不依赖任何其它脚本，取中间位只是避免与
# 官方脚本编号相撞。
#
# 变量未定义时 envsubst 替换成空串，即「同源相对路径」，与改动前的默认行为一致。
set -eu

envsubst '${VITE_API_BASE_URL} ${VITE_BASE_URL}' \
  < /etc/nginx/config.json.template \
  > /usr/share/nginx/html/config.json

echo "app-config: 已生成 config.json -> $(cat /usr/share/nginx/html/config.json)"
