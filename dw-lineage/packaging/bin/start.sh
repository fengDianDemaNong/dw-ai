#!/usr/bin/env bash
# 启动服务
set -e
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

check_java
[ -n "$APP_JAR" ] || die "未在 $LIB_DIR 找到应用 jar"

if is_running; then
  echo "$APP_NAME 已在运行 (pid=$(running_pid))"
  exit 0
fi

# 端口被别的进程占用时必须直接失败。
# 否则健康检查会连上那个进程并误判为「启动成功」，
# 而实际访问到的是别人的服务（历史上因此排查了很久的空白页问题）。
port="$(yaml_get server port)"; port="${port:-8080}"
if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "错误: 端口 $port 已被占用，占用者：" >&2
  lsof -nP -iTCP:"$port" -sTCP:LISTEN | tail -n +1 >&2
  echo "请先停止该进程，或修改 conf/application.yml 中的 server.port" >&2
  exit 1
fi

echo "启动 $APP_NAME ..."
echo "  安装目录: $APP_HOME"
echo "  配置文件: $CONF_DIR/application.yml"
# 环境变量优先，与上面桥接的口径一致；只看 yml 的话，
# 在 env.sh 里 export DB_TYPE 时这里会打出与实际不符的类型
echo "  数据库:   ${DB_TYPE:-$(yaml_get database type)}"

# 凭据加密密钥没配的话，「元数据服务」页面上带凭据的服务（dbx）存不进去。
# 这个值靠环境变量注入，很容易在换个终端重启时忘带，所以启动时明确提醒一次。
# yml 里直接写死值的部署方式也支持，因此两处都要检查。
secret_in_yml="$(yaml_get metadata secret-key)"
# yml 里默认写的是 ${METADATA_SECRET_KEY:} 这类占位符，值其实来自环境变量；
# 不把它排除掉，这个检查就永远认为「已配置」，等于白写
case "$secret_in_yml" in '${'*) secret_in_yml="" ;; esac
if [ -z "$METADATA_SECRET_KEY" ] && [ -z "$secret_in_yml" ]; then
  echo "  提示: 未设置 METADATA_SECRET_KEY，dbx 等需要凭据的元数据服务将无法保存。"
  echo "        可在 $CONF_DIR/env.sh 中 export，重启后生效。"
fi

# 把 conf/env.sh 里 export 的 DB_* 桥接成 Spring 的绑定名，让这几个变量对服务端真正生效。
# 左边一列与 dw-org / dw-model / docker 部署完全同一套名字，运维不用记两套。
#
#   DB_TYPE DB_HOST DB_PORT DB_NAME DB_URL DB_USER DB_PASSWORD
#     -> DATABASE_TYPE DATABASE_HOST DATABASE_PORT DATABASE_NAME DATABASE_URL
#        DATABASE_USERNAME DATABASE_PASSWORD
#
# 为什么不把 conf/application.yml 改成 ${DB_USER:} 这类占位符：
# 那份 yml 还被 bin/init-db.sh 用 yaml_get 按纯文本读（建库要拿账号密码），
# 读到的是 ${DB_USER:} 这个字面量，会把库建到错误的账号上。
# secret-key 那处就是因为同样的原因，才在 start.sh 里单独绕了一道。
#
# 只在 env.sh 里 export 了才桥接：yml 里手填的值照旧生效，
# 两处都填时以 env.sh 为准（环境变量的优先级高于配置文件）。
# DB_URL 填了会盖过 host/port/name，与 database.url 的既有语义一致。
if [ -n "${DB_TYPE:-}" ];     then export DATABASE_TYPE="$DB_TYPE"; fi
if [ -n "${DB_HOST:-}" ];     then export DATABASE_HOST="$DB_HOST"; fi
if [ -n "${DB_PORT:-}" ];     then export DATABASE_PORT="$DB_PORT"; fi
if [ -n "${DB_NAME:-}" ];     then export DATABASE_NAME="$DB_NAME"; fi
if [ -n "${DB_URL:-}" ];      then export DATABASE_URL="$DB_URL"; fi
if [ -n "${DB_USER:-}" ];     then export DATABASE_USERNAME="$DB_USER"; fi
if [ -n "${DB_PASSWORD:-}" ]; then export DATABASE_PASSWORD="$DB_PASSWORD"; fi

cd "$APP_HOME"
# $APP_TAG / $APP_HOME_TAG 是给 status.sh、stop.sh 认人用的标记，
# 必须出现在命令行里，见 common.sh 的说明
nohup "${JAVA_BIN:-java}" $JAVA_OPTS \
  "$APP_TAG" \
  "$APP_HOME_TAG" \
  -Dspring.config.location="file:$CONF_DIR/application.yml" \
  -Dlogging.file.path="$LOG_DIR" \
  -Dweb.static-dir="$APP_HOME/web" \
  -jar "$APP_JAR" \
  >> "$LOG_DIR/stdout.log" 2>&1 &

# pid 文件只作留档，判断存活一律走进程标记
echo $! > "$PID_FILE"

# 等待健康检查通过。先判进程存活再判健康，
# 顺序反过来会在进程已死时仍被别的服务的响应蒙混过关。
for i in $(seq 1 60); do
  sleep 1
  if ! is_running; then
    echo "启动失败，请查看 $LOG_DIR/stdout.log" >&2
    tail -30 "$LOG_DIR/stdout.log" >&2
    rm -f "$PID_FILE"
    exit 1
  fi
  if curl -fs "http://localhost:$port/actuator/health" >/dev/null 2>&1; then
    echo "启动成功 (pid=$(running_pid))"
    echo "  控制台:   http://localhost:$port/"
    echo "  接口文档: http://localhost:$port/swagger-ui.html"
    exit 0
  fi
done

echo "启动超时，请查看 $LOG_DIR/stdout.log"
exit 1
