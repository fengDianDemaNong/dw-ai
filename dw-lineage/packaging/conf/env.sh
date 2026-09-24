#!/usr/bin/env bash
# ================================================================
#  环境变量。bin/common.sh 会自动 source 本文件，
#  在这里 export 的内容对 start.sh / stop.sh / sql-cli.sh 都生效。
#
#  改完执行 bin/restart.sh 生效。
# ================================================================

# ---------------- 元数据服务凭据加密密钥 ----------------
# 「元数据服务」页面上配置的 dbx 登录密码等凭据，会用这个密钥加密后存库。
#
# 不配的话服务照常启动，但保存带凭据的服务会明确报错 ——
# 这是刻意的：宁可保存失败，也不把凭据明文写进数据库。
#
# ⚠️ 换密钥会导致已存的凭据无法解密，需要在页面上重新录入，所以定下来就别改。
# ⚠️ 本文件含密钥，注意文件权限（chmod 600 conf/env.sh）。
#
export METADATA_SECRET_KEY='111111'

# ---------------- 运行模式 ----------------
# standalone（无登录）| standard（本模块本地账号，默认）| multi（登录组织平台）。
# 不设 = standard，即独立部署要显式说出来，不会被兜底兜进去（无认证口子不能默认打开）。
#
# DW_AI_MODE 是三个模块（组织 / 仓建设 / 数据地图）统一的开关，一次部署只配一份；
# 数据地图另有更具体的 LINEAGE_RUN_MODE，两个都设时以它为准。改完 bin/restart.sh 生效。
# export DW_AI_MODE='standard'

# ---------------- 服务间令牌 ----------------
# multi 下必须与组织、仓建设填**同一个值**（三个安装包各填一遍），否则 /internal/v1/** 一律 401，
# 项目镜像同步不过来 —— 页面上的表现是「租户编码未同步」。
# 独立 / 普通模式不需要它。生成方式：openssl rand -hex 32
#
# export MODULE_TOKEN='...'

# ---------------- JVM 参数 ----------------
# 默认 -Xms512m -Xmx2g -XX:+UseG1GC，内存紧张或 SQL 特别大时可调整
# export JAVA_OPTS='-Xms512m -Xmx4g -XX:+UseG1GC -Dfile.encoding=UTF-8'

# ---------------- 数据库账号 ----------------
# 不想把账号密码写进 conf/application.yml 时，在这里 export。
#
# 这一组名字与 dw-org / dw-model 及 docker 部署完全相同，一套部署共用一份配置：
#   DB_TYPE  DB_HOST  DB_PORT  DB_NAME  DB_URL  DB_USER  DB_PASSWORD
# 安装包里三处都认：
#   bin/start.sh    服务端 —— 桥接成 Spring 的绑定名（DATABASE_*）后传给进程
#   bin/init-db.sh  建库建表 —— 直接按「环境变量优先」取值
#   bin/sql-cli.sh  手工执行 SQL —— 同上
# 两处都填时以这里为准（环境变量的优先级高于配置文件）。
#
# ⚠️ 本文件含密码，注意文件权限（chmod 600 conf/env.sh）。
# export DB_USER='dwai'
# export DB_PASSWORD='...'
