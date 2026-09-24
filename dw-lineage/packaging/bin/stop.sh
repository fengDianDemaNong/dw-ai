#!/usr/bin/env bash
# 停止服务
#
# 按进程标记查找，不依赖 pid 文件 —— 安装目录被重建后 pid 文件会丢，
# 而进程还活着占着端口，只认 pid 文件就停不掉了。
set -e
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

pids="$(app_pids)"

if [ -z "$pids" ]; then
  echo "$APP_NAME 未在运行"
  rm -f "$PID_FILE"
  exit 0
fi

echo "停止 $APP_NAME (pid=$(echo "$pids" | tr '\n' ' ' | sed 's/ $//')) ..."
# 一次性把本安装目录下的进程全停掉：残留进程会继续占端口和 H2 数据文件
for pid in $pids; do
  kill "$pid" 2>/dev/null || true
done

for i in $(seq 1 30); do
  sleep 1
  if ! is_running; then
    rm -f "$PID_FILE"
    echo "已停止"
    exit 0
  fi
done

echo "优雅停止超时，强制结束"
for pid in $(app_pids); do
  kill -9 "$pid" 2>/dev/null || true
done

# 强杀后再确认一次，别在进程其实还在的时候报「已停止」
for i in $(seq 1 10); do
  sleep 1
  if ! is_running; then
    rm -f "$PID_FILE"
    echo "已停止"
    exit 0
  fi
done

echo "错误: 无法停止以下进程，请手工处理：" >&2
ps -A -o pid=,command= | grep -F -- "$APP_HOME_TAG" >&2 || true
exit 1
