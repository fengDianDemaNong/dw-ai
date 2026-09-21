# 安装包运行环境。按现场改。JWT_SECRET 须与智仓安装包一致。
# 未设置 DB_URL 时使用安装目录 data/dw_org（H2）。禁止与建模共用同一个 MySQL/PostgreSQL 库名。
#
# MySQL：
# DB_URL=jdbc:mysql://127.0.0.1:3306/dw_org?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8
# DB_USER=dwai
# DB_PASSWORD=dwai
#
# PostgreSQL：
# DB_URL=jdbc:postgresql://127.0.0.1:5432/dw_org
# DB_USER=dwai
# DB_PASSWORD=dwai

APP_JAR_NAME=dw-org-0.1.3.jar
SERVER_PORT=18080
LOG_NAME=dw-org
DW_AI_HOME=
PUBLIC_BASE_URL=http://127.0.0.1:18080

DB_URL=
DB_USER=
DB_PASSWORD=

DW_AI_MODE=multi
DW_AI_RUN_MODE=multi
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

CORS_ORIGINS=*
WEB_STATIC_DIR=
