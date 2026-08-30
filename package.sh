#!/usr/bin/env bash
# 在仓库根目录打包：app/ 业务 jar + libs/ 依赖，输出完整包与仅程序包
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

VERSION="${VERSION:-$(node -p "require('./package.json').version")}"
NAME="dw-ai-${VERSION}"
STAGE="$ROOT/release/${NAME}"
LIBS_CACHE="$ROOT/release/.libs-cache"

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少命令：$1" >&2
    exit 1
  fi
}

need node
need npm
need mvn
need tar

echo "==> 构建控制台"
npm install
VITE_API_BASE="${VITE_API_BASE:-.}" npm run build -w @dw-ai/ui

echo "==> 构建 API（thin jar + 依赖）"
(cd "$ROOT/services/api" && mvn -B -DskipTests package)

JAR="$ROOT/services/api/target/dw-ai-api-${VERSION}.jar"
LIBDIR="$ROOT/services/api/target/lib"
if [[ ! -f "$JAR" ]]; then
  echo "未找到 $JAR" >&2
  exit 1
fi
if [[ ! -d "$LIBDIR" ]]; then
  echo "未找到 $LIBDIR" >&2
  exit 1
fi
if [[ ! -d "$ROOT/ui/dist" ]]; then
  echo "未找到 ui/dist" >&2
  exit 1
fi

DEPS_HASH="$(shasum -a 256 "$ROOT/services/api/pom.xml" | awk '{print $1}')"
if [[ -f "$LIBS_CACHE/.deps-hash" && "$(cat "$LIBS_CACHE/.deps-hash")" == "$DEPS_HASH" ]]; then
  echo "==> 依赖未变，复用 release/.libs-cache"
else
  echo "==> 更新依赖缓存"
  rm -rf "$LIBS_CACHE"
  mkdir -p "$LIBS_CACHE"
  cp "$LIBDIR/"*.jar "$LIBS_CACHE/"
  printf '%s\n' "$DEPS_HASH" >"$LIBS_CACHE/.deps-hash"
fi

echo "==> 组装安装包 $NAME"
rm -rf "$STAGE"
mkdir -p "$STAGE/bin" "$STAGE/conf" "$STAGE/app" "$STAGE/libs/web" "$STAGE/sql" "$STAGE/data" "$STAGE/logs"

cp -R "$ROOT/packaging/bin/." "$STAGE/bin/"
cp -R "$ROOT/packaging/conf/." "$STAGE/conf/"
chmod +x "$STAGE/bin/"*.sh

cp "$JAR" "$STAGE/app/dw-ai-api.jar"
cp "$LIBS_CACHE/"*.jar "$STAGE/libs/"
cp "$LIBS_CACHE/.deps-hash" "$STAGE/libs/.deps-hash"
cp -R "$ROOT/ui/dist/." "$STAGE/libs/web/"
cp "$ROOT/services/api/src/main/resources/db/seed/demo-mysql.sql" "$STAGE/sql/"
cp "$ROOT/services/api/src/main/resources/db/seed/demo-postgresql.sql" "$STAGE/sql/"
printf '%s\n' "$VERSION" >"$STAGE/VERSION"
cat >"$STAGE/README.txt" <<EOF
智仓 DW-AI ${VERSION} 安装包

完整使用说明见解压后对照仓库 docs/user/使用说明.md（或产品包内文档站点）。

目录：
  bin/    start.sh  stop.sh  status.sh  seed.sh
  conf/   env.sh    application.yml
  app/    dw-ai-api.jar（业务代码）
  libs/   第三方 jar ；web/ 控制台静态页
  sql/    演示数据（默认不执行）
  data/   H2 文件库（启动后生成）
  logs/

启动：
  1. 本机 Java 17+。默认不用外置数据库（H2）。
  2. 按需改 conf/env.sh
  3. ./bin/start.sh
  4. 试用：./bin/seed.sh
  5. 浏览器 http://127.0.0.1:8080/  （张三 / 123456）

停止：./bin/stop.sh
EOF

ARCHIVE="$ROOT/release/${NAME}.tar.gz"
tar -C "$ROOT/release" -czf "$ARCHIVE" "$NAME"
echo "==> 完整包 $ARCHIVE"

APP_STAGE="$ROOT/release/${NAME}-app"
rm -rf "$APP_STAGE"
mkdir -p "$APP_STAGE/app" "$APP_STAGE/libs/web" "$APP_STAGE/bin" "$APP_STAGE/sql"
cp "$JAR" "$APP_STAGE/app/dw-ai-api.jar"
cp -R "$ROOT/ui/dist/." "$APP_STAGE/libs/web/"
cp -R "$ROOT/packaging/bin/." "$APP_STAGE/bin/"
chmod +x "$APP_STAGE/bin/"*.sh
cp "$ROOT/services/api/src/main/resources/db/seed/"*.sql "$APP_STAGE/sql/"
printf '%s\n' "$VERSION" >"$APP_STAGE/VERSION"
cat >"$APP_STAGE/README.txt" <<EOF
智仓 DW-AI ${VERSION} 程序增量包（不含 libs/*.jar）

覆盖已有安装目录的 app/、libs/web/、bin/、sql/ 后启动。
依赖变更时请改用完整包，并更新 libs/*.jar。
EOF
APP_ARCHIVE="$ROOT/release/${NAME}-app.tar.gz"
tar -C "$ROOT/release" -czf "$APP_ARCHIVE" "${NAME}-app"
echo "==> 增量包 $APP_ARCHIVE"
echo "    解压后：cd ${NAME} && ./bin/start.sh && ./bin/seed.sh"
