package com.dwai.lineage.dto;

/**
 * 数据目录，带该目录下已登记的表数量。
 *
 * @param isDefault  是否默认目录；导入元数据没指定目录时落到它
 * @param tableCount 该目录下的表数量。列表里就带上，是因为「能不能删」直接取决于它，
 *                   让用户点了删除才被 409 拦住是糟糕的交互
 */
public record DataCatalogResponse(long id,
                                  String name,
                                  boolean isDefault,
                                  String description,
                                  int tableCount) {
}
