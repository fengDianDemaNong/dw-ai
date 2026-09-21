package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 贴建表语句导入元数据。
 *
 * @param ddl         一段或多段 {@code CREATE TABLE}；非建表语句会被忽略而不是报错，
 *                    方便直接把整个脚本粘进来
 * @param catalogName 导入到哪个数据目录；留空用默认目录。
 *                    <b>建表语句里自己写了三段名（{@code create table cat.db.t}）时以语句为准</b> ——
 *                    语句里的信息更具体，而且一次粘进多个目录的建表语句是常见做法，
 *                    用下拉框去覆盖它反而会把它们全挤进同一个目录里撞唯一键
 */
@Schema(description = "用 CREATE TABLE 导入本地元数据。非建表语句会被忽略。")
public record MetaDdlImportRequest(

        @Schema(description = "方言", example = "doris", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "数据库类型不能为空")
        String dbType,

        @Schema(description = "一段或多段 CREATE TABLE，可夹杂其它语句", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "建表语句不能为空")
        @Size(max = MAX_DDL_LENGTH, message = "建表语句长度超过上限 " + MAX_DDL_LENGTH + " 字符")
        String ddl,

        @Schema(description = "导入到哪个数据目录；语句已写三段名时以语句为准")
        @Size(max = 128, message = "数据目录名称长度不能超过 128")
        String catalogName,

        @Schema(description = "是否覆盖 source=MANUAL 的行，默认 false")
        Boolean overwriteManual) {

    /** 与 SQL 解析的上限保持一致。 */
    public static final int MAX_DDL_LENGTH = 200_000;

    public boolean overwriteManualOrDefault() {
        return Boolean.TRUE.equals(overwriteManual);
    }
}
