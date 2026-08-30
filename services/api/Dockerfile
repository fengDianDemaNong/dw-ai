FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 dwai \
    && mkdir -p /app/data /app/app /app/libs \
    && chown -R dwai:dwai /app
COPY --from=build --chown=dwai:dwai /app/target/dw-ai-api-0.1.3.jar /app/app/dw-ai-api.jar
COPY --from=build --chown=dwai:dwai /app/target/lib /app/libs
USER dwai
ENV DW_AI_HOME=/app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=10 \
  CMD curl -fsS http://127.0.0.1:8080/api/health >/dev/null || exit 1
ENTRYPOINT ["java", "-cp", "/app/app/dw-ai-api.jar:/app/libs/*", "com.dwai.platform.DwaiApplication"]
