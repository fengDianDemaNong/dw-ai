#!/usr/bin/env bash
# 智仓打包。用法：./dw-model/package.sh [all|api|ui]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
cd "$REPO"

MODE="${1:-all}"
case "$MODE" in
  all|api|ui) ;;
  *) echo "用法: $0 [all|api|ui]" >&2; exit 1 ;;
esac

VERSION="${VERSION:-$(node -p "require('./package.json').version")}"
NAME="dw-model-${VERSION}"
APP_JAR_NAME="dw-model-${VERSION}.jar"
STAGE="$HERE/release/${NAME}"
LIBS_CACHE="$HERE/release/.libs-cache"
JAR="$HERE/api/target/${APP_JAR_NAME}"
LIBDIR="$HERE/api/target/lib"
DIST="$HERE/ui/dist"
WS="@dw-ai/ui"

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少命令：$1" >&2
    exit 1
  fi
}

need tar
mkdir -p "$HERE/release"

patch_env() {
  local dest="$1"
  if [[ "$(uname)" == Darwin ]]; then
    sed -i '' "s/^APP_JAR_NAME=.*/APP_JAR_NAME=${APP_JAR_NAME}/" "$dest"
  else
    sed -i "s/^APP_JAR_NAME=.*/APP_JAR_NAME=${APP_JAR_NAME}/" "$dest"
  fi
}

# 安装包的 sql/ 只是给人看的参考副本，运行时并不从这里读 —— SeedMain 走 classpath，
# 真正生效的是 libs/ 里的 dw-common jar。身份层（identity*.sql）住在 dw-common，
# 本模块只剩 members.sql 与 business-*.sql，所以要跨模块拷一次，否则现场打开 sql/
# 看不到 t-xinghe / p-trade 是从哪来的。
copy_seed_sql() {
  local dest="$1"
  cp "$HERE/api/src/main/resources/db/seed/"*.sql "$dest/"
  cp "$REPO/dw-common/src/main/resources/db/seed/"*.sql "$dest/"
}

build_ui() {
  need node
  need npm
  export VITE_API_BASE="${VITE_API_BASE:-.}"
  export VITE_ORG_ORIGIN="${VITE_ORG_ORIGIN:-http://127.0.0.1:18080}"
  export VITE_WAREHOUSE_ORIGIN="${VITE_WAREHOUSE_ORIGIN:-http://127.0.0.1:18081}"
  export VITE_LINEAGE_ORIGIN="${VITE_LINEAGE_ORIGIN:-http://127.0.0.1:5173}"
  echo "==> 构建智仓控制台"
  npm install
  npm run build -w "$WS"
  [[ -d "$DIST" ]] || { echo "未找到 $DIST" >&2; exit 1; }
}

build_api() {
  need mvn
  echo "==> 构建智仓 API"
  # dw-common 是独立模块，先 install 到本地仓库；之后本服务仍按自己的 pom 独立构建。
  # 仓库根的聚合 pom 是「一次构建全部」用的，这里刻意不用它 —— 单服务打包
  # 不应把其它服务也拉进来编译。
  mvn -B -DskipTests -q -f "$REPO/dw-common/pom.xml" install
  (cd "$HERE/api" && mvn -B -DskipTests clean package)
  [[ -f "$JAR" ]] || { echo "未找到 $JAR" >&2; exit 1; }
  [[ -d "$LIBDIR" ]] || { echo "未找到 $LIBDIR" >&2; exit 1; }
  local deps_hash
  # 依赖缓存要跟着两个 pom 一起失效：dw-common 加一个依赖，本服务的 libs/ 也会变
  deps_hash="$(cat "$REPO/dw-common/pom.xml" "$HERE/api/pom.xml" | shasum -a 256 | awk '{print $1}')"
  if [[ -f "$LIBS_CACHE/.deps-hash" && "$(cat "$LIBS_CACHE/.deps-hash")" == "$deps_hash" ]]; then
    echo "==> 依赖未变，复用 dw-model/release/.libs-cache"
  else
    echo "==> 更新依赖缓存"
    rm -rf "$LIBS_CACHE"
    mkdir -p "$LIBS_CACHE"
    cp "$LIBDIR/"*.jar "$LIBS_CACHE/"
    printf '%s\n' "$deps_hash" >"$LIBS_CACHE/.deps-hash"
  fi
}

pack_ui() {
  local ui_name="${NAME}-ui"
  local ui_stage="$HERE/release/${ui_name}"
  echo "==> 组装 $ui_name"
  rm -rf "$ui_stage"
  mkdir -p "$ui_stage/html"
  cp -R "$DIST/." "$ui_stage/html/"
  cp "$HERE/ui/nginx.conf" "$ui_stage/nginx.conf"
  printf '%s\n' "$VERSION" >"$ui_stage/VERSION"
  cat >"$ui_stage/README.txt" <<EOF
智仓 ${VERSION} 前端包

html/       静态页
nginx.conf  反代 /api 到 model-api:8080

Docker：仓库根 docker compose up -d --build model-ui
或：docker build -f dw-model/ui/Dockerfile .
EOF
  tar -C "$HERE/release" -czf "$HERE/release/${ui_name}.tar.gz" "$ui_name"
  echo "==> 前端包 $HERE/release/${ui_name}.tar.gz"
}

pack_api() {
  local api_name="${NAME}-api"
  local api_stage="$HERE/release/${api_name}"
  echo "==> 组装 $api_name"
  rm -rf "$api_stage"
  mkdir -p "$api_stage/bin" "$api_stage/conf" "$api_stage/app" "$api_stage/libs" "$api_stage/sql" "$api_stage/data" "$api_stage/logs"
  cp -R "$HERE/packaging/bin/." "$api_stage/bin/"
  cp -R "$HERE/packaging/conf/." "$api_stage/conf/"
  chmod +x "$api_stage/bin/"*.sh
  patch_env "$api_stage/conf/env.sh"
  cp "$JAR" "$api_stage/app/${APP_JAR_NAME}"
  cp "$LIBS_CACHE/"*.jar "$api_stage/libs/"
  cp "$LIBS_CACHE/.deps-hash" "$api_stage/libs/.deps-hash"
  copy_seed_sql "$api_stage/sql"
  printf '%s\n' "$VERSION" >"$api_stage/VERSION"
  cat >"$api_stage/README.txt" <<EOF
智仓 ${VERSION} 后端安装包（不含控制台）

解压后 ./bin/start.sh → http://127.0.0.1:18081/api/health
ORG_BASE_URL、JWT_SECRET 须与租户管理一致。
EOF
  tar -C "$HERE/release" -czf "$HERE/release/${api_name}.tar.gz" "$api_name"
  echo "==> 后端包 $HERE/release/${api_name}.tar.gz"
}

pack_all() {
  echo "==> 组装 $NAME"
  rm -rf "$STAGE"
  mkdir -p "$STAGE/bin" "$STAGE/conf" "$STAGE/app" "$STAGE/libs" "$STAGE/web" "$STAGE/sql" "$STAGE/data" "$STAGE/logs"
  cp -R "$HERE/packaging/bin/." "$STAGE/bin/"
  cp -R "$HERE/packaging/conf/." "$STAGE/conf/"
  chmod +x "$STAGE/bin/"*.sh
  patch_env "$STAGE/conf/env.sh"
  cp "$JAR" "$STAGE/app/${APP_JAR_NAME}"
  cp "$LIBS_CACHE/"*.jar "$STAGE/libs/"
  cp "$LIBS_CACHE/.deps-hash" "$STAGE/libs/.deps-hash"
  cp -R "$DIST/." "$STAGE/web/"
  copy_seed_sql "$STAGE/sql"
  printf '%s\n' "$VERSION" >"$STAGE/VERSION"
  cat >"$STAGE/README.txt" <<EOF
智仓 ${VERSION} 安装包（前后端一体，Java 出页面）

解压后 ./bin/start.sh → http://127.0.0.1:18081/
多租户请先启动租户管理（18080）。
EOF
  tar -C "$HERE/release" -czf "$HERE/release/${NAME}.tar.gz" "$NAME"
  echo "==> 完整包 $HERE/release/${NAME}.tar.gz"

  local app_stage="$HERE/release/${NAME}-app"
  rm -rf "$app_stage"
  mkdir -p "$app_stage/app" "$app_stage/web" "$app_stage/bin" "$app_stage/sql"
  cp "$JAR" "$app_stage/app/${APP_JAR_NAME}"
  cp -R "$DIST/." "$app_stage/web/"
  cp -R "$HERE/packaging/bin/." "$app_stage/bin/"
  chmod +x "$app_stage/bin/"*.sh
  copy_seed_sql "$app_stage/sql"
  printf '%s\n' "$VERSION" >"$app_stage/VERSION"
  tar -C "$HERE/release" -czf "$HERE/release/${NAME}-app.tar.gz" "${NAME}-app"
  echo "==> 增量包 $HERE/release/${NAME}-app.tar.gz"
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
