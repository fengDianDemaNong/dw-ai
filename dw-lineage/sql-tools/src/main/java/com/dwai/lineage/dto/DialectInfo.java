package com.dwai.lineage.dto;

import com.dwai.lineage.enums.DatabaseTypeEnum;

/**
 * 方言及其能力，供前端决定是否提示用户。
 *
 * @param type                 方言标识，即请求参数 {@code dbType} 的取值
 * @param columnLevelLineage   是否支持列级血缘。当前 13 种方言在拿到表结构后均支持
 * @param ddlMetadataSupported 能否从 SQL 里的 {@code CREATE TABLE} 提取表结构。
 *                             为 {@code false} 时必须依赖外部元数据服务，
 *                             否则 {@code select *}、聚合、列重命名等场景会缺列
 * @param note                 面向用户的说明，为空表示无特殊限制
 */
public record DialectInfo(String type,
                          boolean columnLevelLineage,
                          boolean ddlMetadataSupported,
                          String note) {

    private static final String NEED_EXTERNAL_METADATA =
            "该方言的 CREATE TABLE 无法提取表结构，请配置外部元数据服务；"
                    + "仅依赖 SQL 内建表语句时 select * 等场景会缺列";

    public static DialectInfo of(DatabaseTypeEnum dialect) {
        boolean ddl = dialect.isDdlMetadataSupported();
        return new DialectInfo(dialect.getType(), true, ddl, ddl ? "" : NEED_EXTERNAL_METADATA);
    }
}
