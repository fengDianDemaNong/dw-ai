智仓 DW-AI 0.1.0 安装包

目录：
  bin/    start.sh  stop.sh  status.sh
  conf/   env.sh    application.yml
  libs/   dw-ai-api-0.1.0.jar   web/（控制台静态页）
  logs/   运行日志（启动后生成）

启动：
  1. 准备 PostgreSQL，改 conf/env.sh 里的 DB_*
  2. 本机需 Java 17+
  3. ./bin/start.sh
  4. 浏览器打开 http://127.0.0.1:8080/ ，开发登录「张三」

停止：./bin/stop.sh
