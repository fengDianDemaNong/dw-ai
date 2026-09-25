#!/usr/bin/env bash
# 数据地图 / 血缘分析打包。用法：./dw-lineage/package.sh [all|api|ui]
#   all  完整安装包（Java 同时出控制台）+ 前端静态包
#   api  仅后端安装包
#   ui   仅前端静态包（给 Nginx）
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
cd "$HERE"

MODE="${1:-all}"
case "$MODE" in
  all|api|ui) ;;
  *) echo "用法: $0 [all|api|ui]" >&2; exit 1 ;;
esac

VERSION="${VERSION:-$(node -p "require('./package.json').version")}"
NAME="dw-lineage-${VERSION}"
STAGE="$HERE/release/${NAME}"
DIST="$HERE/ui/dist"
UI_WS="dw-lineage/ui"

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少命令：$1" >&2
    exit 1
  fi
}

need tar
mkdir -p "$HERE/release"

build_ui() {
  need node
  need npm
  echo "==> 构建数据地图控制台"
  # 依赖是仓库级动作：dw-lineage/ui 已并入根 npm workspaces，node_modules 在仓库根，
  # 不能在 ui 子目录里单独装（装出来的树与 CI/开发都不同）。见 ADR-0014。
  [ -d "$REPO/node_modules" ] || (cd "$REPO" && CI=true npm install)
  # 安装包由后端进程直接托管前端，挂在根路径，
  # 因此必须用 base=/ 构建，与后端 WebStaticConfig 的资源路径一致。
  # 这里仍从 ui 目录调 vite：npx --no-install 会沿路径向上找到根 node_modules/.bin/vite，
  # 之所以不换成 npm run，就是为了保住这个显式的 --base=/。
  (cd "$HERE/ui" && npx --no-install vite build --base=/)
  [[ -d "$DIST" ]] || { echo "未找到 $DIST" >&2; exit 1; }
}

build_api() {
  need mvn
  echo "==> 构建数据地图 API"
  (cd "$HERE/api" && mvn -B -DskipTests clean package -q)
  # artifactId=sql-tools，version=1.0-SNAPSHOT → jar 名 sql-tools-1.0-SNAPSHOT.jar
  local jar
  jar="$(ls "$HERE"/api/target/sql-tools-*.jar 2>/dev/null | grep -v sources | head -1 || true)"
  [[ -f "$jar" ]] || { echo "未找到 api/target/sql-tools-*.jar" >&2; exit 1; }
  echo "$jar" >"$HERE/release/.last-jar"
}

# 取最近一次构建的 jar 路径
jar_path() {
  cat "$HERE/release/.last-jar"
}

# 从 db/migration 派生安装包用的 01_schema/02_init_data
# （Flyway 权威来源，不在 release/sql 维护第二份副本）
derive_sql() {
  local dest="$1"
  for d in h2 mysql postgresql; do
    mkdir -p "$dest/sql/$d"
    cp "$HERE/api/src/main/resources/db/migration/$d/V1__schema.sql" "$dest/sql/$d/01_schema.sql" 2>/dev/null || true
    cp "$HERE/api/src/main/resources/db/migration/$d/V2__init_data.sql" "$dest/sql/$d/02_init_data.sql" 2>/dev/null || true
  done
}

pack_ui() {
  local ui_name="${NAME}-ui"
  local ui_stage="$HERE/release/${ui_name}"
  echo "==> 组装 $ui_name"
  rm -rf "$ui_stage"
  mkdir -p "$ui_stage/html"
  cp -R "$DIST/." "$ui_stage/html/"
  cp "$HERE/ui/nginx.conf" "$ui_stage/nginx.conf" 2>/dev/null || true
  printf '%s\n' "$VERSION" >"$ui_stage/VERSION"
  cat >"$ui_stage/README.txt" <<EOF
数据地图 ${VERSION} 前端包

html/       静态页（放到 Nginx root）
nginx.conf  反代 /api 到后端（${BACKEND_HOST}:${BACKEND_PORT}，默认 backend:18082）

Docker：仓库根 docker compose -f dw-lineage/docker-compose.yml up -d --build frontend
或：在仓库根执行 docker build -f dw-lineage/ui/Dockerfile .
     （上下文必须是仓库根而不是 ui 目录 —— 前端依赖 workspace 的 packages/engine）
EOF
  tar -C "$HERE/release" -czf "$HERE/release/${ui_name}.tar.gz" "$ui_name"
  echo "==> 前端包 $HERE/release/${ui_name}.tar.gz"
}

pack_api() {
  local api_name="${NAME}-api"
  local api_stage="$HERE/release/${api_name}"
  echo "==> 组装 $api_name"
  rm -rf "$api_stage"
  mkdir -p "$api_stage/bin" "$api_stage/conf" "$api_stage/lib" "$api_stage/sql" "$api_stage/data" "$api_stage/logs"
  cp -R "$HERE/packaging/bin/." "$api_stage/bin/"
  cp -R "$HERE/packaging/conf/." "$api_stage/conf/"
  chmod +x "$api_stage/bin/"*.sh
  cp "$(jar_path)" "$api_stage/lib/"
  derive_sql "$api_stage"
  printf '%s\n' "$VERSION" >"$api_stage/VERSION"
  cat >"$api_stage/README.txt" <<EOF
数据地图 ${VERSION} 后端安装包（不含控制台）

解压后 ./bin/start.sh → http://127.0.0.1:18082/
前端请另用 ${NAME}-ui.tar.gz 或本服务 docker compose。
EOF
  tar -C "$HERE/release" -czf "$HERE/release/${api_name}.tar.gz" "$api_name"
  echo "==> 后端包 $HERE/release/${api_name}.tar.gz"
}

pack_all() {
  echo "==> 组装 $NAME"
  rm -rf "$STAGE"
  mkdir -p "$STAGE/bin" "$STAGE/conf" "$STAGE/lib" "$STAGE/web" "$STAGE/sql" "$STAGE/data" "$STAGE/logs"
  cp -R "$HERE/packaging/bin/." "$STAGE/bin/"
  cp -R "$HERE/packaging/conf/." "$STAGE/conf/"
  chmod +x "$STAGE/bin/"*.sh
  cp "$(jar_path)" "$STAGE/lib/"
  cp -R "$DIST/." "$STAGE/web/"
  derive_sql "$STAGE"
  cp "$HERE/release/INSTALL.md" "$STAGE/README.md" 2>/dev/null || true
  printf '%s\n' "$VERSION" >"$STAGE/VERSION"
  tar -C "$HERE/release" -czf "$HERE/release/${NAME}.tar.gz" "$NAME"
  echo "==> 完整包 $HERE/release/${NAME}.tar.gz"
}

case "$MODE" in
  ui)
    build_ui
    pack_ui
    ;;
  api)
    build_api
    pack_api
    ;;
  all)
    build_ui
    build_api
    pack_all
    pack_api
    pack_ui
    ;;
esac
echo "    解压完整包后：cd ${NAME} && ./bin/start.sh"
