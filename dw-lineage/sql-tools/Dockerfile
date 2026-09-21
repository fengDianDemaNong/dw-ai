# 运行镜像。
#
# 依赖说明：sqlflow 与 superior-sql-parser 是本地 SNAPSHOT 依赖，不在公共仓库中，
# 因此不在镜像内从源码构建，而是先在宿主机构建好 jar 再打包：
#
#   cd ../sqlflow              && mvn install -DskipTests
#   cd ../superior-sql-parser  && mvn install -DskipTests
#   cd ../sql-tools            && mvn package -DskipTests
#   docker build -t sql-tools .
#
FROM eclipse-temurin:21-jre

WORKDIR /app

# 非 root 运行
RUN groupadd -r app && useradd -r -g app app

COPY target/sql-tools-*.jar app.jar

# H2 数据目录必须在镜像里先建好并归属给 app：
# docker-compose 把命名卷挂在这里，而命名卷会以「镜像中该路径的属主」初始化。
# 镜像里没有这个目录的话，卷就是 root 属主，非 root 运行的容器写不进去，
# 启动时会挂在 AccessDeniedException: /app/data/dw_lineage.lock.db
RUN mkdir -p /app/data
RUN chown -R app:app /app
USER app

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prd \
    GRAVITINO_URL=http://gravitino:8090 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
