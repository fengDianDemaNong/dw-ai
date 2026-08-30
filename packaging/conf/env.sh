# 安装包运行环境。按现场改，不要回写成仓库密钥。
# 未设置 DB_URL 时使用安装目录 data/ 下的 H2 文件库（无需外置数据库）。

SERVER_PORT=8080
DW_AI_HOME=

# 元数据库：留空 = H2。MySQL / PostgreSQL 示例见下方注释。
DB_URL=
DB_USER=
DB_PASSWORD=
# DB_TYPE=h2|mysql|postgresql   # 可选，一般可按 JDBC URL 判断

# MySQL：
# DB_URL=jdbc:mysql://127.0.0.1:3306/dwai?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8
# DB_USER=dwai
# DB_PASSWORD=dwai

# PostgreSQL：
# DB_URL=jdbc:postgresql://127.0.0.1:5432/dwai
# DB_USER=dwai
# DB_PASSWORD=dwai

DW_AI_MODE=multi
DW_AI_LLM_SECRET=dw-ai-llm-dev-secret-change-me-32b
SECURITY_MODE=dev
JWT_SECRET=dw-ai-dev-secret-change-me-please-32b
ALLOW_DEV_LOGIN=true
CASDOOR_ISSUER=
CASDOOR_JWK=
CASDOOR_CLIENT_ID=
CASDOOR_ORG_MAP=built-in:t-xinghe,casbin:t-xinghe

# 外部分析库 / 调度（默认关）
SR_ENABLED=false
SR_URL=jdbc:mysql://127.0.0.1:9030/dwai
SR_USER=root
SR_PASSWORD=

DS_ENABLED=false
DS_URL=http://127.0.0.1:12345/dolphinscheduler
DS_TOKEN=
DS_PROJECT_CODE=0

CORS_ORIGINS=*
# 留空则 start.sh 使用安装目录 libs/web
WEB_STATIC_DIR=
