package com.dwai.lineage.service.metadata.dbx;

/**
 * dbx {@code /api/schema/columns} 返回的一个字段（结构化）。
 *
 * <p>{@link DbxClient#columns} 只取列名 —— 血缘解析只需要那个。
 * 但同步进元数据目录时，类型与注释才是用户最关心的信息，因此另有
 * {@link DbxClient#columnDetails} 返回本类型。
 *
 * <p>字段名对应实测响应：
 * {@code {"name","data_type","is_nullable","is_primary_key","comment", ...}}。
 * <b>注意 dbx 没有分区列的概念</b>，这里也就没有对应字段。
 */
public record DbxColumn(String name,
                        String dataType,
                        String comment,
                        boolean nullable,
                        boolean primaryKey) {
}
