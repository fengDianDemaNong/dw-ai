#!/usr/bin/env bash
# 公共变量与函数，供其它脚本 source

# 定位安装根目录（bin 的上一级），使脚本可从任意目录调用
BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "$BIN_DIR/.." && pwd)"

APP_NAME="sql-lineage"
CONF_DIR="$APP_HOME/conf"
LIB_DIR="$APP_HOME/lib"
LOG_DIR="$APP_HOME/logs"
SQL_DIR="$APP_HOME/sql"
PID_FILE="$APP_HOME/logs/${APP_NAME}.pid"

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

# ---------------------------------------------------------------
# 进程标识
#
# JVM 没法真的改进程名（试过 exec -a：macOS 的 /usr/bin/java 是个 shim，
# 会把 argv[0] 丢掉），通行做法是打一个专属的系统属性做标记 ——
# 它会原样出现在完整命令行里，ps 就能精确定位。
#
# 带上安装目录：同一台机器上部署多份时，各自只认自己的进程，
# 不会互相 stop 掉对方。
# ---------------------------------------------------------------
APP_TAG="-Dapp.name=${APP_NAME}"
APP_HOME_TAG="-Dapp.home=${APP_HOME}"

# JVM 参数，可在 conf/env.sh 中覆盖
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8}"

[ -f "$CONF_DIR/env.sh" ] && . "$CONF_DIR/env.sh"

mkdir -p "$LOG_DIR"

APP_JAR="$(ls "$LIB_DIR"/sql-tools-*.jar 2>/dev/null | head -1)"

die() { echo "错误: $*" >&2; exit 1; }

check_java() {
  command -v "${JAVA_BIN:-java}" >/dev/null 2>&1 || die "未找到 java，请安装 JDK 21 或设置 JAVA_HOME"
  local ver
  ver="$("${JAVA_BIN:-java}" -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')"
  [ -n "$ver" ] && [ "$ver" -lt 21 ] && die "需要 JDK 21，当前为 $ver"
  return 0
}

# 本安装目录下正在运行的进程 pid（可能多个），按进程标记查找。
#
# 不依赖 pid 文件：安装目录被重建（如重新出包）时 pid 文件会一起消失，
# 而进程还活着并占着端口，此时只认 pid 文件就会误报「未在运行」，
# 接着 start.sh 又因端口被占而失败 —— 这个坑踩过。
app_pids() {
  # 先把 ps 结果取到变量里再过滤：直接管道给 grep 的话，
  # grep 自己的命令行也含有要匹配的字符串，会把自己算进去
  local snapshot
  snapshot="$(ps -A -o pid=,command= 2>/dev/null)" || return 0
  # 用 grep -F 定长匹配：路径里的 / . - 在正则里都有特殊含义
  printf '%s\n' "$snapshot" \
    | grep -F -- "$APP_TAG" \
    | grep -F -- "$APP_HOME_TAG" \
    | awk '{print $1}'
}

# 主进程 pid（正常只有一个）
running_pid() {
  app_pids | head -1
}

is_running() {
  [ -n "$(app_pids)" ]
}

# 从 conf/application.yml 读取顶层某段下的配置项（简易解析，够用即可）
yaml_get() {
  local section="$1" key="$2"
  awk -v s="$section" -v k="$key" '
    $0 ~ "^"s":" {inside=1; next}
    inside && /^[a-zA-Z]/ {inside=0}
    inside && $1 == k":" {
      $1=""; sub(/^ /,""); gsub(/^["\x27]|["\x27]$/,"");
      sub(/[ \t]*#.*$/,"");
      print; exit
    }
  ' "$CONF_DIR/application.yml" 2>/dev/null
}
