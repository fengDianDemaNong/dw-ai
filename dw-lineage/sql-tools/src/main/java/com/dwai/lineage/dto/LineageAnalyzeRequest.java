package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 血缘解析请求。
 *
 * @param dbType        数据库/引擎类型，取值见 {@code GET /api/dbType}
 * @param querySql      待解析的 SQL，可包含多条语句
 * @param columnName    可选。只看某一列的血缘，支持裸列名或 {@code schema.table.column} 全限定名
 * @param isCreateTable 是否用 SQL 中的 CREATE TABLE 语句作为表结构元数据来源
 * @param sourceId      可选。选用的元数据服务配置 id（见 {@code GET /api/metadata-sources}）。
 *                      不传表示不接外部元数据，只用 DDL + 结构推断
 * @param metalake      可选，仅 Gravitino 来源需要；不传则用该配置 extraConfig 中的默认值
 * @param catalog       可选，同上
 * @param includeTemp   是否在图上保留临时表。<b>默认 false</b>，与保存行为保持一致 ——
 *                      默认看到的就是会被存进库里的那张图，不会出现「页面上有、库里没有」
 */
@Schema(description = "字段级血缘试解析请求。结果不落库。")
public record LineageAnalyzeRequest(

        @Schema(description = "方言，取值见 GET /api/dialects", example = "doris", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "数据库类型不能为空")
        String dbType,

        @Schema(description = "待解析 SQL，可含多条语句", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "SQL 不能为空")
        @Size(max = MAX_SQL_LENGTH, message = "SQL 长度超过上限 " + MAX_SQL_LENGTH + " 字符，请拆分后再解析")
        String querySql,

        @Schema(description = "只看某一列：裸列名或 schema.table.column")
        String columnName,

        @Schema(description = "true 时把 SQL 内 CREATE TABLE 当作表结构")
        Boolean isCreateTable,

        @Schema(description = "外部元数据服务 id，见 GET /api/metadata-sources；不传则只用 DDL + 推断")
        Long sourceId,

        @Schema(description = "Gravitino metalake，不传则用该来源 extraConfig 默认值")
        String metalake,

        @Schema(description = "Gravitino catalog，不传则用该来源 extraConfig 默认值")
        String catalog,

        @Schema(description = "是否在图上保留临时表，默认 false，与保存行为一致")
        Boolean includeTemp) {

    /** 默认不含临时表：所见即所存。 */
    public boolean includeTempOrDefault() {
        return Boolean.TRUE.equals(includeTemp);
    }

    /** SQL 文本长度上限，避免超大输入耗尽内存或拖垮 ANTLR 解析。 */
    public static final int MAX_SQL_LENGTH = 200_000;

    public boolean createTableAsMetadata() {
        return Boolean.TRUE.equals(isCreateTable);
    }
}
