package com.dwai.lineage.service;

import com.dwai.lineage.dto.LineageGraph;
import com.dwai.lineage.service.metadata.provider.TableDescriptor;

import java.util.Locale;
import java.util.Map;

/**
 * 一次解析的完整产出：给前端看的图 + 给保存用的描述快照。
 *
 * <h2>为什么描述不塞进 {@link LineageGraph}</h2>
 * {@code LineageGraph} 是 HTTP 响应体。描述属性只有保存路径要用，
 * 塞进去等于让每次解析都往前端多传一份用不上的数据，还得记着别让它进契约。
 *
 * @param graph       血缘图
 * @param descriptors <b>表全名（已规范化成三段、且转成小写）</b> → 该表的描述。
 *                    小写是刻意的：血缘侧的 {@code full_name} 保留 SQL 里的原始大小写
 *                    （要显示在图上），而元数据来源给的可能是另一种写法，
 *                    按原样查会大面积落空。取用一律走 {@link #descriptorOf}
 */
public record AnalyzedLineage(LineageGraph graph,
                              Map<String, TableDescriptor> descriptors) {

    public AnalyzedLineage {
        descriptors = descriptors == null ? Map.of() : descriptors;
    }

    public static AnalyzedLineage of(LineageGraph graph) {
        return new AnalyzedLineage(graph, Map.of());
    }

    /** 按表全名取描述，大小写不敏感。查不到返回 null —— 这张表没有可用的元数据。 */
    public TableDescriptor descriptorOf(String tableFullName) {
        if (tableFullName == null) {
            return null;
        }
        return descriptors.get(tableFullName.toLowerCase(Locale.ROOT));
    }

    /** 建 {@link #descriptors} 时统一走这里，保证 key 与 {@link #descriptorOf} 一致。 */
    public static String normalizeKey(String tableFullName) {
        return tableFullName == null ? null : tableFullName.toLowerCase(Locale.ROOT);
    }
}
