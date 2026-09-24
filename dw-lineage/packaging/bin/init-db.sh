#!/usr/bin/env bash
# 初始化数据库（可选，供离线手工安装 / DBA 审阅 / 应急重建使用）。
#
# 正常情况无需本脚本：服务启动时 Flyway 会自动建表灌初始数据（与 dw-org / dw-model 一致）。
# 已用本脚本建好的库，服务启动时 Flyway baseline 接管，不会重复建表。
# 升级已有库优先让服务自动跑新迁移，离线场景再执行 sql/upgrade/ 下对应脚本。
#
# 使用内置的 Java SQL 工具执行脚本，不依赖 mysql / psql 客户端。
set -e
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

CREATE_DB=0
FORCE=""
for arg in "$@"; do
  case "$arg" in
    --create-db) CREATE_DB=1 ;;
    --force)     FORCE="--force" ;;
    -h|--help)
      echo "用法: init-db.sh [--create-db] [--force]"
      echo "  --create-db  连同创建数据库一起执行（需要有建库权限的账号）"
      echo "  --force      某条语句失败时继续执行后续语句"
      echo
      echo "注意：本脚本用于<离线手工建库>，重复执行会因为表已存在而失败。"
      echo "      服务默认启动时由 Flyway 自动建表；升级已有库走 sql/upgrade/ 或让 Flyway 执行。"
      exit 0
      ;;
    *) die "未知参数: $arg" ;;
  esac
done

# 与 start.sh 同一口径：conf/env.sh 里 export 的优先，没 export 才读 application.yml。
# yaml_get 是纯文本解析，看不见环境变量 —— 不兜这一层的话，只在 env.sh 里切库
# 会出现「服务连 MySQL、本脚本却按 h2 建表」这种一半对一半错的状态。
DB_TYPE="${DB_TYPE:-$(yaml_get database type)}"
[ -n "$DB_TYPE" ] || die "无法确定 database.type：$CONF_DIR/application.yml 与环境变量 DB_TYPE 都没有"

echo "数据库类型: $DB_TYPE"

# H2 是内嵌库，数据文件会在首次连接时自动创建；表结构由 Flyway 托管（空库自动建表），
# 本脚本只是「离线手工建库」的另一条等价路径。

SCHEMA_DIR="$SQL_DIR/$DB_TYPE"
[ -d "$SCHEMA_DIR" ] || die "找不到脚本目录: $SCHEMA_DIR"

CLI="$BIN_DIR/sql-cli.sh"

if [ "$CREATE_DB" = "1" ]; then
  echo
  echo "==> 创建数据库"
  # 建库要连到实例的默认库，而不是尚不存在的目标库
  DB_HOST="${DB_HOST:-$(yaml_get database host)}"
  DB_PORT="${DB_PORT:-$(yaml_get database port)}"
  # 账号与库名都让 conf/env.sh 里的 export 优先，与 start.sh 的口径一致
  DB_USER="${DB_USER:-$(yaml_get database username)}"
  DB_PASS="${DB_PASSWORD:-$(yaml_get database password)}"

  DB_NAME="${DB_NAME:-$(yaml_get database name)}"
  [ -n "$DB_NAME" ] || die "配置中缺少 database.name"

  # 建库语句按配置中的库名动态生成，而不是执行 sql/*/00_create_database.sql，
  # 否则脚本里硬编码的库名与 conf 配置不一致时会建错库。
  case "$DB_TYPE" in
    mysql)
      DB_PORT="${DB_PORT:-3306}"
      ADMIN_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/mysql?useSSL=false&allowPublicKeyRetrieval=true"
      CREATE_SQL="CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
      # MySQL 的 IF NOT EXISTS 天然幂等
      CREATE_EXTRA=""
      ;;
    postgresql)
      DB_PORT="${DB_PORT:-5432}"
      ADMIN_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/postgres"
      CREATE_SQL="CREATE DATABASE ${DB_NAME};"
      # PostgreSQL 不支持 CREATE DATABASE IF NOT EXISTS，库已存在时会报错，
      # 用 --force 让它作为可忽略的情况跳过
      CREATE_EXTRA="--force"
      ;;
    *) die "不支持的数据库类型: $DB_TYPE" ;;
  esac

  echo "  目标库: $DB_NAME"
  "$CLI" -u "$ADMIN_URL" -n "$DB_USER" -p "$DB_PASS" \
         -e "$CREATE_SQL" $FORCE $CREATE_EXTRA || {
    echo "  （库可能已存在，继续）"
  }
fi

echo
echo "==> 创建表结构"
"$CLI" -f "$SCHEMA_DIR/01_schema.sql" $FORCE

echo
echo "==> 写入初始数据（默认租户/项目、内置元数据源、结构版本）"
"$CLI" -f "$SCHEMA_DIR/02_init_data.sql" $FORCE

echo
echo "初始化完成，现在可以执行 bin/start.sh 启动服务。"
