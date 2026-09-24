#!/usr/bin/env bash
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

port="$(yaml_get server port)"; port="${port:-8080}"

pids="$(app_pids)"

if [ -n "$pids" ]; then
  echo "$APP_NAME 运行中 (pid=$(echo "$pids" | tr '\n' ' ' | sed 's/ $//'))"
  # 与 start.sh 同口径：先在 env.sh 里找，找不到才读配置文件，
  # 否则在 env.sh 里切了库时这里会显示成 yml 里那个旧类型
  echo "  数据库: ${DB_TYPE:-$(yaml_get database type)}"
  echo "  端口:   $port"
  health="$(curl -fs "http://localhost:$port/actuator/health" 2>/dev/null || echo '无法访问')"
  echo "  健康:   $health"

  # 同一安装目录下正常只该有一个进程。出现多个通常是之前启动失败留下的残留，
  # 它们会互相抢端口和 H2 数据文件，如实提示而不是装作没看见。
  if [ "$(echo "$pids" | wc -l | tr -d ' ')" -gt 1 ]; then
    echo "  ⚠️  检测到多个进程，可能是残留，建议执行 bin/stop.sh 后重新启动"
  fi
  exit 0
fi

echo "$APP_NAME 未在运行"

# 端口被占但进程不是本应用：多半是上一个版本的残留或别的服务，
# 直接说清楚，省得用户对着 start.sh 的「端口已被占用」发懵
if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "  注意: 端口 $port 正被其它进程占用，启动前需先处理："
  lsof -nP -iTCP:"$port" -sTCP:LISTEN >&2
fi

exit 1
