package com.dwai.lineage.dto;

import java.util.List;

/**
 * 保存结果。
 *
 * <p>一段 SQL 里有几张目标表，就产生几个版本 —— 所以这里是列表而不是单个版本。
 *
 * @param versions 本次新建的版本，每张目标表一个
 */
public record LineageSaveResponse(int tables, int columns, int edges, List<SavedVersion> versions) {

    /**
     * @param targetTable 该版本描述的是哪张表的血缘
     * @param versionNo   在这张表内递增的版本号
     */
    public record SavedVersion(long versionId,
                               long targetTableId,
                               String targetTable,
                               int versionNo,
                               int edges) {
    }
}
