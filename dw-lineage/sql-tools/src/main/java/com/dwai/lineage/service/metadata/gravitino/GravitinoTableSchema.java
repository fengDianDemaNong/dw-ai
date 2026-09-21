package com.dwai.lineage.service.metadata.gravitino;

import java.util.List;

/**
 * 从 Gravitino 读到的一张表的完整结构，供同步进元数据目录使用。
 *
 * <p>取代了原先返回 {@code "column:名字,类型：X,注释：Y"} 这种拼给人看的字符串的做法 ——
 * 那种格式没法可靠地再解析回来，注释为 null 时还会拼出字面量 {@code "null"}。
 */
public record GravitinoTableSchema(String name,
                                   String comment,
                                   List<GravitinoColumn> columns) {
}
