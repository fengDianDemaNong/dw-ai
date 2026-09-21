package com.dwai.lineage.conf;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置，访问 {@code /swagger-ui.html} 或 {@code /swagger-ui/index.html}。
 *
 * <p>文档由代码自动生成，不会像手写文档那样与实现脱节。
 */
@Configuration
public class OpenApiConfig {

    public static final String HEADER_TENANT = "X-Tenant-Id";
    public static final String HEADER_PROJECT = "X-Project-Id";

    @Bean
    public OpenAPI sqlToolsOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SQL 血缘分析 API")
                        .version("v1")
                        .description("""
                                解析 SQL 得到字段级 / 表级血缘，并按租户、项目隔离保存。

                                ## 给调用方（含 AI）的选型说明
                                先看「想做什么」，再选接口，不要凭路径名字猜测：
                                - **只解析、不落库**：字段级用 `POST /api/lineage/analyze`；只要表与表关系、没有表结构时用 `POST /api/lineage/table`。
                                - **解析并写入当前项目**：`POST /api/lineage/save`（会重新解析 SQL，不要把前端图回传）。
                                - **查已经保存的血缘图 / 版本**：`GET /api/lineage/graph`、`/api/catalog/**`。
                                - **维护真实表结构（给字段级解析当元数据）**：`/api/meta/**`（贴 DDL、从 Gravitino/dbx 同步、手工改）。不要和 `/api/catalog/**` 混用。
                                - **配置外部元数据服务**：`/api/metadata-sources`；旧版直连 Gravitino 浏览/解析在 `/api/mate/**`，新代码优先用 metadata-sources + meta/sync。
                                - **配置数据目录、临时表过滤规则**：`/api/data-catalogs`。
                                - **租户 / 项目 CRUD**：`/api/tenants`、`/api/tenants/{tenantId}/projects`。这些接口**不读请求头**，对象由路径参数指定。
                                - **确认当前请求头解析成了谁**：`GET /api/context`。
                                - **概览数字**：项目口径 `GET /api/stats/project`，租户口径 `GET /api/stats/tenant`。

                                ## 请求头
                                除租户管理 CRUD 外，业务数据按头隔离：
                                - `X-Tenant-Id`：租户数字 id。不传则落到默认租户（通常为 1）。
                                - `X-Project-Id`：项目数字 id。不传则落到默认项目。租户统计接口会忽略此头。
                                头存在但不是数字时直接 4xx，不会静默回退。

                                ## 解析要点
                                - 字段级血缘需要表结构：SQL 内 CREATE TABLE（`isCreateTable=true`）或外部 / 本地元数据。
                                - trino / presto / sqlserver 无法从 CREATE TABLE 抽表结构，必须接外部元数据；能力见 `GET /api/dialects`。
                                - 多语句脚本部分失败容忍：失败见 `failedStatements`，结构未知的表见 `unresolvedTables`。
                                """)
                        .license(new License().name("Apache 2.0")))
                .addTagsItem(tag("租户与项目管理",
                        "跨租户的管理面：建改删租户/项目。除 GET /api/context 外都不读 X-Tenant-Id / X-Project-Id，以路径参数为准。"))
                .addTagsItem(tag("血缘解析",
                        "当场解析 SQL。analyze=字段级试解析不落库；table=表级无需元数据；validate/keywords 给编辑器。要落库请用「已保存血缘」里的 save。"))
                .addTagsItem(tag("已保存血缘",
                        "当前项目里已经落库的血缘：表清单、上下游、全局搜索、历史版本、保存。与 /api/meta 分开存储。"))
                .addTagsItem(tag("元数据目录",
                        "数据库里真实存在的表结构，供字段级解析查用。三种写入：贴 DDL、外部同步、手工改。"))
                .addTagsItem(tag("元数据服务配置",
                        "Gravitino / dbx 连接配置。响应不含凭据明文。解析或同步时用返回的 id 作为 sourceId。"))
                .addTagsItem(tag("数据目录配置",
                        "项目内数据目录（表全名第一段）以及临时库表过滤规则。"))
                .addTagsItem(tag("统计概览",
                        "首页数字。project 看当前项目；tenant 看整个租户（忽略 X-Project-Id）。不接受查询参数。"))
                .addTagsItem(tag("Gravitino直连（旧）",
                        "兼容旧调用：不经过 metadata-sources 配置页，直接打 Gravitino。新集成请改用元数据服务 + /api/meta/sync。"))
                .components(new Components()
                        .addParameters(HEADER_TENANT, header(HEADER_TENANT,
                                "当前租户数字 id。业务接口按此隔离数据；不传则使用服务端默认租户。租户管理 CRUD 忽略此头。"))
                        .addParameters(HEADER_PROJECT, header(HEADER_PROJECT,
                                "当前项目数字 id。业务接口按此隔离数据；不传则使用服务端默认项目。租户统计与租户管理 CRUD 忽略此头。")));
    }

    /**
     * 给除租户管理 CRUD 以外的接口挂上租户/项目请求头，方便 Swagger「Try it out」和 AI 选型。
     */
    @Bean
    public OpenApiCustomizer tenantHeaderCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            for (PathItem path : openApi.getPaths().values()) {
                for (Operation op : path.readOperations()) {
                    if (op.getTags() != null && op.getTags().contains("租户与项目管理")
                            && !"context".equals(op.getOperationId())) {
                        continue;
                    }
                    op.addParametersItem(new Parameter().$ref("#/components/parameters/" + HEADER_TENANT));
                    op.addParametersItem(new Parameter().$ref("#/components/parameters/" + HEADER_PROJECT));
                }
            }
        };
    }

    private static Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }

    private static Parameter header(String name, String description) {
        return new Parameter()
                .in("header")
                .name(name)
                .description(description)
                .required(false)
                .schema(new StringSchema().example("1"));
    }
}
