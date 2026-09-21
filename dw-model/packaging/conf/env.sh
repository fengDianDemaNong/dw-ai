# 安装包运行环境。按现场改。JWT_SECRET 须与租户管理安装包一致。
# 未设置 DB_URL 时使用安装目录 data/dw_mode（H2）。禁止与租户管理共用同一个 MySQL/PostgreSQL 库名。
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

DW_AI_MODE=multi
BOOTSTRAP_ADMIN_USER=admin
BOOTSTRAP_ADMIN_PASSWORD=admin123
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
