# 构建上下文是仓库根（见 docker-compose.yml 的 context），因为 dw-common 是独立模块，
# 不能只 COPY 本目录。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# 先把共享模块 install 进构建容器的本地仓库；之后本服务仍按自己的 pom 独立构建 ——
# 与开发时的 `mvn -f dw-common/pom.xml install && mvn -f dw-model/api/pom.xml test` 完全一致，
# 不依赖仓库根的聚合 pom（聚合只用于「一次构建全部」）。
COPY dw-common/pom.xml dw-common/pom.xml
COPY dw-common/src dw-common/src
RUN mvn -B -DskipTests -f dw-common/pom.xml install

COPY dw-model/api/pom.xml dw-model/api/pom.xml
COPY dw-model/api/src dw-model/api/src
RUN mvn -B -DskipTests -f dw-model/api/pom.xml package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 dwai \
    && mkdir -p /app/data /app/app /app/libs \
    && chown -R dwai:dwai /app
COPY --from=build --chown=dwai:dwai /src/dw-model/api/target/dw-model-0.1.3.jar /app/app/dw-model-0.1.3.jar
COPY --from=build --chown=dwai:dwai /src/dw-model/api/target/lib /app/libs
USER dwai
ENV DW_AI_HOME=/app
# 端口与镜像外一致（组织 18080 / 仓建设 18081 / 数据地图 18082），不搞「容器内 8080」那套。
EXPOSE 18081
HEALTHCHECK --interval=15s --timeout=3s --retries=10 \
  CMD curl -fsS http://127.0.0.1:18081/api/health >/dev/null || exit 1
ENTRYPOINT ["java", "-cp", "/app/app/dw-model-0.1.3.jar:/app/libs/*", "com.dwai.platform.DwaiApplication"]
