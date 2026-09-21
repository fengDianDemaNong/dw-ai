package com.dwai.lineage.service;


import com.dwai.lineage.service.metadata.gravitino.GravitinoTableSchema;

import java.util.List;

/**
 * Gravitino 元数据浏览与解析。
 *
 * <p>每个方法的第一个参数 {@code sourceId} 指向 {@code metadata_source} 中的一条 Gravitino 配置。
 * 传 null 表示使用 yml 里的 {@code gravitino.url}，用于兼容配置化之前的调用方式。
 *
 * @author:wang
 * @createTime:2025/5/20 17:01
 * @version:1.0
 */
public interface GravitinoMetadataServic {

    List<String> getSupportedMateLakes(Long sourceId);


    List<String> getSupportedCatalogs(Long sourceId, String mateLake);


    List<String> getSupportedSchemas(Long sourceId, String mateLake, String catalog);

    /** 某个 schema 下的表名。 */
    List<String> getSupportedTables(Long sourceId, String mateLake, String catalog, String schema);

    /**
     * 一张表的完整结构，供同步进元数据目录使用。
     *
     * <p>早先这里返回的是 {@code "column:名字,类型：X,注释：Y"} 这种拼给人看的字符串，
     * 没法可靠地再解析回来（分隔符半角全角混用，注释为 null 时还会拼出字面量 "null"），
     * 而且当时没有任何调用方。改成结构化返回。
     */
    GravitinoTableSchema getTableSchema(Long sourceId, String mateLake, String catalog,
                                        String schema, String table);

    com.dwai.lineage.dto.LineageGraph analyzeSqlLineage(Long sourceId, String mateLake, String catalog,
                                              String columnName, String sql);
}
