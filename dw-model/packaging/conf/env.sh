# 安装包运行环境。按现场改。JWT_SECRET 须与租户管理安装包一致。
# 未设置 DB_URL 时使用安装目录 data/<DWAI_DB_FILE>（H2，见下方）。禁止与租户管理共用同一个
# MySQL/PostgreSQL 库名。
#
# MySQL：
# DB_URL=jdbc:mysql://127.0.0.1:3306/dw_mode?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8
# DB_USER=dwai
# DB_PASSWORD=dwai
#
# PostgreSQL：
# DB_URL=jdbc:postgresql://127.0.0.1:5432/dw_mode
# DB_USER=dwai
# DB_PASSWORD=dwai

APP_JAR_NAME=dw-model-0.1.3.jar
SERVER_PORT=18081
LOG_NAME=dw-model
DW_AI_HOME=
ORG_BASE_URL=http://127.0.0.1:18080
SERVICE_BASE_URL=http://127.0.0.1:18081
PUBLIC_BASE_URL=http://127.0.0.1:18081
RULES_BASE=http://127.0.0.1:7080

DB_URL=
DB_USER=
DB_PASSWORD=

DW_AI_MODE=standard
# H2 库文件名，只在不设 DB_URL 时生效（最终库文件是 data/<DWAI_DB_FILE>.mv.db）。
# bin/start.sh 与 bin/seed-demo.sh 都 source 本文件 —— 服务进程与灌数据进程因此读同一个库。
# 改这里就是两边一起改，**不要**只在某一个脚本里另加一个值，否则会出现「建表在 A、数据在 B」。
# 服务侧读的是 spring 属性 dwai.db-file，环境变量 DWAI_DB_FILE 由 Spring 的宽松绑定映射过去。
DWAI_DB_FILE=dw_mode
# 服务间令牌：multi 下必须与组织、数据地图填**同一个值**。留空 = /internal/v1/** 一律 401，
# 本模块的租户与项目全部靠组织同步灌入，同步断了的表现是「组织里建了项目，
# 这里打开却是『还没有可进入的项目』」（见根 README「三种运行模式」与 ADR-0012）。
MODULE_TOKEN=
BOOTSTRAP_ADMIN_USER=admin
BOOTSTRAP_ADMIN_PASSWORD=123456
DW_AI_LLM_SECRET=dw-ai-llm-dev-secret-change-me-32b
SECURITY_MODE=dev
JWT_SECRET=dw-ai-dev-secret-change-me-please-32b
ALLOW_DEV_LOGIN=true
CASDOOR_ISSUER=
CASDOOR_JWK=
CASDOOR_CLIENT_ID=
CASDOOR_ORG_MAP=built-in:t-xinghe,casbin:t-xinghe

SR_ENABLED=false
SR_URL=jdbc:mysql://127.0.0.1:9030/dwai
SR_USER=root
SR_PASSWORD=

DS_ENABLED=false
DS_URL=http://127.0.0.1:12345/dolphinscheduler
DS_TOKEN=
DS_PROJECT_CODE=0

CORS_ORIGINS=*
WEB_STATIC_DIR=
