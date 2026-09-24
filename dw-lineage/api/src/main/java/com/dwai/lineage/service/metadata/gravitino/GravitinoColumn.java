package com.dwai.lineage.service.metadata.gravitino;

/**
 * Gravitino 中的一个字段（结构化）。
 *
 * @param partition 是否为分区列。Gravitino 的分区信息在表级 {@code partitioning()} 上，
 *                  这里按列名回填 —— 分区列对 Hive / Spark 血缘是必需的
 */
public record GravitinoColumn(String name,
                              String dataType,
                              String comment,
                              boolean nullable,
                              boolean partition) {
}
