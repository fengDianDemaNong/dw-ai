#!/usr/bin/env bash
# SQL 命令行工具（用法类似 beeline / trino cli）
#
# 驱动已打包在应用 jar 内，无需另外安装 mysql / psql 客户端。
# 不带参数进入交互模式；-e 执行单条 SQL；-f 执行脚本。
set -e
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

check_java
[ -n "$APP_JAR" ] || die "未在 $LIB_DIR 找到应用 jar"

cd "$APP_HOME"
# Spring Boot 的可执行 jar 需要用 PropertiesLauncher 才能启动自定义 main 类。
# 主类名要与 api/src/main/java/com/dwai/lineage/cli/SqlCli.java 一致 ——
# 包路径从 org.qq 改到 com.dwai.lineage 时这里漏改了，导致 bin/init-db.sh
# 一路 ClassNotFoundException（离线建库整条路走不通），改名后记得对一遍这个文件。
exec "${JAVA_BIN:-java}" \
  -Dfile.encoding=UTF-8 \
  -Dloader.main=com.dwai.lineage.cli.SqlCli \
  -cp "$APP_JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
