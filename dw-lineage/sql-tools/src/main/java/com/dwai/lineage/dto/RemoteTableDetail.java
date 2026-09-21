package com.dwai.lineage.dto;

import java.util.List;

/**
 * 从外部元数据服务<b>只读</b>取到的一张表的结构。
 *
 * <p>供「元数据」页切换到某个数据服务后浏览用 —— 只是看，不落库。
 * 想要留下来得走导入（{@code POST /api/meta/sync}）。
 *
 * <h2>为什么不复用 {@code MetaColumnResponse}</h2>
 * 那个类型带 {@code id} / {@code tableId}，对应的是本地 {@code meta_column} 的行。
 * 远程表在本地压根没有行，塞 0 进去只会让前端的 row-key 全撞在一起，
 * 而且会让人误以为这条记录已经存在于本地目录里。
 *
 * @param name    表名（不含库名）
 * @param comment 表中文名。两种来源都给得出 —— dbx 的表注释在
 *                {@code /api/schema/tables} 里（不在 columns 接口里），
 *                见 {@code DbxClient.tableComment}
 * @param columns 字段，按源端顺序
 */
public record RemoteTableDetail(String name, String comment, List<RemoteColumn> columns) {

    public RemoteTableDetail {
        columns = columns == null ? List.of() : columns;
    }

    /**
     * 远程表的一个字段。
     *
     * <p>字段集是两种来源的<b>并集</b>，各自给不出的那部分保持默认值：
     *
     * <ul>
     *   <li>Gravitino 给得出 {@code partition}，给不出 {@code primaryKey}</li>
     *   <li>dbx 给得出 {@code primaryKey}，给不出 {@code partition}</li>
     * </ul>
     *
     * <p><b>缺的一律留 false，不要推断</b>。尤其是 {@code partition}：
     * 血缘解析要靠它判断分区列，编造一个出来会让整条链路的结果都偏掉，
     * 比留空糟得多。
     *
     * @param ordinal 从 1 开始的列序，前端拿它当 row-key
     */
    public record RemoteColumn(int ordinal,
                               String name,
                               String dataType,
                               String comment,
                               boolean nullable,
                               boolean partition,
                               boolean primaryKey) {
    }
}
