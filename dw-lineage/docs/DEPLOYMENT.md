# 部署说明

## 方式一：docker compose（推荐）

后端 jar 需先在宿主机构建：

```bash
(cd api && mvn package -DskipTests)

docker compose up -d
```

| 服务 | 端口 | 说明 |
|---|---|---|
| frontend | 80 | nginx，`/api` 反代到后端 |
| backend | 8080 | Spring Boot |

访问 <http://localhost/lineage/>。

指定 Gravitino 地址：

```bash
GRAVITINO_URL=http://your-gravitino:8090 docker compose up -d
```

## 方式二：独立部署

```bash
java -jar sql-tools-1.0-SNAPSHOT.jar --spring.profiles.active=prd
```

前端产物 `ui/dist` 交给 nginx，并配置 `/api` 反代到后端
（参考 `ui/nginx.conf`）。

> **不要**让前端直连后端地址。前端默认走相对路径 `/api`，
> 由 nginx 同源反代，这样构建产物不绑定任何主机，也不需要开跨域。

---

## 配置项

全部支持环境变量注入，**不要把地址和密码写进配置文件**。

### 数据库

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DB_TYPE` | `h2` | `h2` / `mysql` / `postgresql`；驱动与 Flyway 脚本目录都按它推导，不可单独配置 |
| `DB_URL` | 由 `DB_TYPE` 推导 | 完整连接串，填了会忽略下面的 host/port/name |
| `DB_HOST` | `localhost` | 仅 mysql / postgresql |
| `DB_PORT` | `3306` / `5432` | 仅 mysql / postgresql，留空用默认值 |
| `DB_NAME` | `dw_lineage` | 仅 mysql / postgresql |
| `DB_USER` | `sa`（h2） | 用户名，与 dw-org / dw-model 同口径 |
| `DB_USERNAME` | — | 上面那个的旧名字，`DB_USER` 为空时回落到它；新配置不用填 |
| `DB_PASSWORD` | 空 | |
| `DB_H2_PATH` | `${DW_AI_HOME}/data/dw_lineage` | 仅 type=h2。**安装包形态不认这个变量**，改 `conf/application.yml` 的 `database.h2-path`（原因见下） |

这一组在三种部署形态里名字相同：docker compose 从 `.env` 读；安装包在 `conf/env.sh` 里
`export`（`bin/start.sh` 会桥接成 Spring 的绑定名传给服务端，`bin/init-db.sh` 与
`bin/sql-cli.sh` 直接按环境变量优先取）；源码 `java -jar` 时放在 `java` 之前。安装包也可以
完全不设，直接改 `conf/application.yml` —— 两条路都通，都填时以环境变量为准。

> **安装包的 `conf/application.yml` 不能写 `${DB_USER:}` 这类占位符。**
> `bin/init-db.sh` 用 `yaml_get` 以纯文本方式读它取账号建库，读到的是占位符字面量本身，
> 会把库建到错误的账号上 —— 所以这一项只能靠 `conf/env.sh` 注入，不能靠占位符回落。
> 源码形态的 `api/src/main/resources/application.yml` 不经 `yaml_get`，没有这个限制，
> 两边写法不同是有意的，不要「顺手统一」。

切换到 MySQL：

```bash
# 环境变量要放在 java 之前。写在 -jar 之后会被当成程序参数，不会成为 JVM 属性，
# 配置不生效且不报错 —— 排查起来很费时间。
DB_TYPE=mysql \
DB_URL='jdbc:mysql://host:3306/dw_lineage' \
DB_USER=user DB_PASSWORD=pass \
java -jar sql-tools.jar --spring.profiles.active=prd,mysql
```

切换到 PostgreSQL 把 `mysql` 换成 `postgresql`。

> **MySQL 必须 8.0+**。血缘图遍历用递归 CTE，5.7 不支持，会直接报错。
> PostgreSQL 需 12+。

### 元数据服务

| 变量 | 默认值 |
|---|---|
| `GRAVITINO_URL` | `http://gravitino:8090`（prd profile） |

### 解析保护

| 变量 | 默认值 | 说明 |
|---|---|---|
| `sql.parse.timeout-seconds` | 60 | 单次解析超时 |
| `sql.parse.stack-size-mb` | 16 | 解析线程栈大小，深嵌套 SQL 需要更大的栈 |
| `sql.parse.max-concurrent` | 16 | 并发解析上限，超出直接拒绝 |

### 跨域

| 变量 | 默认值 |
|---|---|
| `CORS_ALLOWED_ORIGINS` | prd 下为空 |

生产环境走 nginx 同源反代，**不需要开跨域**。仅在前后端确实不同源时才配置白名单。

---

## 数据库准备

**表结构由 Flyway 托管**（与 dw-org / dw-model 一致）：空库启动时自动建表并灌初始数据，
无需手工执行脚本。

```sql
-- MySQL
CREATE DATABASE dw_lineage DEFAULT CHARACTER SET utf8mb4;

-- PostgreSQL
CREATE DATABASE dw_lineage;
```

库本身仍需先建好（应用账号通常没有建库权限），建表则交给 Flyway：
在 `conf/env.sh` 里 export 好库类型与账号（或改 `conf/application.yml`）后直接 `bin/start.sh`。

需要离线手工建库 / DBA 先审阅 DDL 时，才按顺序执行对应方言的脚本（`sql/` 目录随安装包发布）：

```bash
bin/sql-cli.sh < sql/mysql/01_schema.sql      # 建表
bin/sql-cli.sh < sql/mysql/02_init_data.sql   # 默认租户/项目/数据目录等初始数据
```

已用脚本建好的库，服务启动时 Flyway `baseline` 接管，不会重复建表。
H2 不必先建库；`bin/init-db.sh` 把上面两步串起来了。

### 版本升级

升级优先让服务启动自动跑新迁移（`db/migration` 下的新版本脚本）。
无外网 / 停机窗口等需要离线升级的场景，按 `sql/upgrade/<方言>/<起始版本>_to_<目标版本>.sql`
逐个执行，**不要跳版本** —— 中间版本的脚本可能带数据订正，不只是改表结构。
升级脚本会改动存量数据，**执行前先备份**。

> 涉及唯一键改写的脚本（`1.0.2_to_1.0.3.sql` 与 `1.0.3_to_1.0.4.sql`
> 都要给存量表补上数据目录段）会先做冲突检测：有冲突时先把冲突的表名 SELECT 出来，
> 再以除零错误中止，不会默默覆盖数据。看到 `Division by zero` 就往上翻那几行查询结果。
>
> `1.0.3_to_1.0.4.sql` 还会给 `meta_table` / `lineage_table` 的 `catalog_name`
> 加上 `NOT NULL`。它归位存量时用的是**每个项目实际的默认目录名**，
> 不是写死的 `default`，所以改过默认目录的环境也能正确升级。

---

## 健康检查与监控

| 端点 | 说明 |
|---|---|
| `/actuator/health` | 健康检查，容器编排用 |
| `/actuator/metrics` | 运行指标 |
| `/actuator/info` | 应用信息 |

后端 Dockerfile 已内置 `HEALTHCHECK`。

---

## 上线检查清单

- [ ] 数据库已创建；空库启动时 Flyway 自动建表（或已按 `sql/<方言>/` 离线建好，Flyway 会 baseline 接管）
- [ ] Flyway 迁移全部应用成功（启动日志无 migration/validation 错误）
- [ ] MySQL 版本 ≥ 8.0 / PostgreSQL ≥ 12
- [ ] `GRAVITINO_URL` 指向真实地址（若使用外部元数据）
- [ ] 前端产物中无硬编码后端地址：
      `grep -rE '192\.168\.|localhost:8080' dist/assets/*.js` 应无输出
- [ ] nginx 已配置 `/api` 反代，且超时大于后端解析超时（默认 60s）
- [ ] `CORS_ALLOWED_ORIGINS` 未误设为 `*`
- [ ] 日志级别为 INFO（DEBUG 会打印 SQL 原文，可能含敏感数据）

---

## 安全说明

- 服务内部错误只返回 `traceId`，堆栈与内网地址不外泄，排查时凭 traceId 查日志
- SQL 原文仅在 DEBUG 级别记录，生产环境不要开 DEBUG
- SQL 长度上限 200,000 字符，解析有超时与并发上限保护
- **当前没有内置认证鉴权**，请部署在内网或在网关层做访问控制
