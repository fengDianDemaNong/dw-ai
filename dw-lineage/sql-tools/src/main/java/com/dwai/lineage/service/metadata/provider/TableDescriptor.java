package com.dwai.lineage.service.metadata.provider;

import java.util.Locale;
import java.util.Map;

/**
 * 一张表的<b>描述属性</b>快照：中文名、备注、表类型，以及各字段的类型与中文名。
 *
 * <h2>为什么要单独走一条通道</h2>
 * 血缘解析本身只关心名字：{@code OutputColumn} 只有 {@code getColumn()}，
 * {@code SchemaTable} 的列是 {@code List<String>} —— sqlflow 的管道里根本没有
 * 描述信息的位置。而这些信息在解析当时是<b>已经拿到了的</b>
 * （本地目录读的是 {@code MetaTableRow}，Gravitino 读的是 {@code Table}，
 * dbx 那个接口的响应里也带着），只是过去在最后一步被丢掉。
 *
 * <p>所以这里开一条与 {@code SchemaTable} 并行的旁路，把描述带到保存那一步，
 * 随血缘一起落库。
 *
 * <h2>为什么要落库而不是查的时候关联</h2>
 * 血缘侧与元数据侧的<b>数据目录段天然对不上</b>：元数据从 Gravitino / dbx 同步过来，
 * 挂在源端的 catalog 下（{@code hive_prod.ods.orders}）；而人写 SQL 基本不写三段，
 * 解析后按默认目录补全（{@code default.ods.orders}）。靠 {@code full_name} 关联
 * 永远对不上，页面上就一直取不到中文名。
 *
 * <p>两个 catalog 本来就是两回事，不该去追平。用谁的元数据解析的，就存谁的描述。
 *
 * @param comment   表中文名
 * @param tableType 表类型（全量表/拉链表之类）。只有本地元数据目录有这个概念，
 *                  远程来源留空
 * @param remark    备注
 * @param columns   列名（<b>小写</b>）→ 该列的描述。大小写归一见 {@link #columnOf}
 */
public record TableDescriptor(String comment,
                              String tableType,
                              String remark,
                              Map<String, ColumnDescriptor> columns) {

    public TableDescriptor {
        columns = columns == null ? Map.of() : columns;
    }

    /** 只有表级描述、没有列描述时的便捷构造。 */
    public static TableDescriptor ofTable(String comment, String tableType, String remark) {
        return new TableDescriptor(comment, tableType, remark, Map.of());
    }

    /**
     * 取某一列的描述。
     *
     * <p>大小写不敏感：血缘侧的列名保留 SQL 里的原始写法（要显示在图上），
     * 而元数据来源给的可能是另一种写法。按原样查会大面积落空。
     */
    public ColumnDescriptor columnOf(String columnName) {
        if (columnName == null) {
            return null;
        }
        return columns.get(columnName.trim().toLowerCase(Locale.ROOT));
    }

    /** 一整份描述都是空的 —— 存进去也没意义，调用方据此跳过。 */
    public boolean isEmpty() {
        return blank(comment) && blank(tableType) && blank(remark) && columns.isEmpty();
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    /** 建 {@link #columns} 时统一走这里，保证 key 的大小写与 {@link #columnOf} 一致。 */
    public static String normalizeColumnKey(String columnName) {
        return columnName == null ? null : columnName.trim().toLowerCase(Locale.ROOT);
    }
}
