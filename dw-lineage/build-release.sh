#!/usr/bin/env bash
#
# 打出可分发的安装包（本仓库：后端 sql-tools + 前端 sql-tools-vue）。
#
#   ./build-release.sh              完整构建
#   ./build-release.sh --skip-web   跳过前端构建
#
# 产物：dist/sql-lineage-<版本>.tar.gz
#
set -e

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="${VERSION:-1.0.0}"
PKG_NAME="sql-lineage-${VERSION}"
BUILD_DIR="$ROOT/dist/$PKG_NAME"

SKIP_WEB=0
for arg in "$@"; do
  case "$arg" in
    --skip-web) SKIP_WEB=1 ;;
    *) echo "未知参数: $arg"; exit 1 ;;
  esac
done

step() { echo; echo "==> $*"; }

# ---------------------------------------------------------------
# 1. 后端
# ---------------------------------------------------------------
step "构建后端"
(cd "$ROOT/sql-tools" && mvn -B -ntp clean package -Dmaven.test.skip=true -q)

JAR="$(ls "$ROOT"/sql-tools/target/sql-tools-*.jar | grep -v sources | head -1)"
[ -f "$JAR" ] || { echo "未找到后端 jar"; exit 1; }

# ---------------------------------------------------------------
# 2. 前端
# ---------------------------------------------------------------
if [ "$SKIP_WEB" = "0" ]; then
  step "构建前端"
  (cd "$ROOT/sql-tools-vue" && {
    if [ ! -d node_modules ]; then
      command -v pnpm >/dev/null 2>&1 || { echo "未找到 pnpm，无法安装前端依赖"; exit 1; }
      # CI=true 避免 pnpm 在无 TTY 时因清理 node_modules 而中断
      CI=true pnpm install --no-frozen-lockfile
    fi
    # 安装包由后端进程直接托管前端，挂在根路径，
    # 因此必须用 base=/ 构建；.env.production 里的 /sql-tools/ 是给
    # nginx 部署在子路径的场景用的，用错会导致资源 404、页面空白。
    npx --no-install vite build --base=/
  })
else
  step "跳过前端构建"
fi

# ---------------------------------------------------------------
# 3. 组装目录
# ---------------------------------------------------------------
step "组装安装包"

# 先停掉上一次装在这里的实例。
# 直接 rm -rf 会把 logs/*.pid 一起删掉，而进程还活着占着 8080：
# 之后 stop.sh 报「未在运行」、start.sh 报「端口已被占用」，两头对不上。
if [ -x "$BUILD_DIR/bin/stop.sh" ]; then
  echo "  停止已安装的实例"
  "$BUILD_DIR/bin/stop.sh" || true
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"/{bin,conf,sql,lib,web,logs,data}

cp -r "$ROOT/release/bin/." "$BUILD_DIR/bin/"
cp -r "$ROOT/release/conf/." "$BUILD_DIR/conf/"
cp -r "$ROOT/release/sql/." "$BUILD_DIR/sql/"
cp "$JAR" "$BUILD_DIR/lib/"

# 建表脚本以 db/migration（Flyway 权威来源）为单一来源，这里派生出安装包里的
# 01_schema.sql / 02_init_data.sql —— 不在 release/sql 里手工维护第二份副本，
# 避免「安装包脚本 vs 迁移脚本」再次漂移。安装包内文件名保持不变，init-db.sh 无需改动。
for d in h2 mysql postgresql; do
  mkdir -p "$BUILD_DIR/sql/$d"
  cp "$ROOT/sql-tools/src/main/resources/db/migration/$d/V1__schema.sql" "$BUILD_DIR/sql/$d/01_schema.sql"
  cp "$ROOT/sql-tools/src/main/resources/db/migration/$d/V2__init_data.sql" "$BUILD_DIR/sql/$d/02_init_data.sql"
done

if [ -d "$ROOT/sql-tools-vue/dist" ]; then
  cp -r "$ROOT/sql-tools-vue/dist/." "$BUILD_DIR/web/"
else
  echo "  警告: 未找到前端产物，web 目录为空，服务将只提供接口"
fi

cp "$ROOT/release/INSTALL.md" "$BUILD_DIR/README.md" 2>/dev/null || true

chmod +x "$BUILD_DIR"/bin/*.sh
# logs 与 data 需要保留空目录
touch "$BUILD_DIR/logs/.gitkeep" "$BUILD_DIR/data/.gitkeep"

# ---------------------------------------------------------------
# 4. 打包
# ---------------------------------------------------------------
step "打包"
(cd "$ROOT/dist" && tar czf "${PKG_NAME}.tar.gz" "$PKG_NAME")

echo
echo "完成: dist/${PKG_NAME}.tar.gz"
echo "     解压后执行 bin/start.sh 即可启动"
du -sh "$ROOT/dist/${PKG_NAME}.tar.gz" | awk '{print "     大小: "$1}'
