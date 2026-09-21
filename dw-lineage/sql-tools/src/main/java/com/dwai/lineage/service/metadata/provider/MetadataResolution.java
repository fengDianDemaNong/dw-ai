package com.dwai.lineage.service.metadata.provider;

import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.sqlflow.metadata.SchemaTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次元数据解析的结果。
 *
 * @param resolved    成功拿到结构的表
 * @param unresolved  没查到的表；调用方据此提示用户「这些表结构未知，select * 无法展开」
 * @param warnings    解析过程中的告警，例如某个元数据服务超时
 * @param descriptors 与 {@code resolved} 并行的<b>描述属性</b>（中文名、字段类型…）。
 *                    血缘解析用不到它，纯粹是搭个便车带到保存那一步随血缘落库 ——
 *                    理由见 {@link TableDescriptor}。来源给不出描述时这里没有对应条目，
 *                    所以 key 集合可能比 {@code resolved} 小
 */
public record MetadataResolution(Map<QualifiedObjectName, SchemaTable> resolved,
                                 Set<QualifiedObjectName> unresolved,
                                 List<String> warnings,
                                 Map<QualifiedObjectName, TableDescriptor> descriptors) {

    public MetadataResolution {
        resolved = resolved == null ? Map.of() : resolved;
        unresolved = unresolved == null ? Set.of() : unresolved;
        warnings = warnings == null ? List.of() : warnings;
        descriptors = descriptors == null ? Map.of() : descriptors;
    }

    /** 不带描述的构造，给拿不到描述的来源（如 InferredMetadataProvider）用。 */
    public MetadataResolution(Map<QualifiedObjectName, SchemaTable> resolved,
                              Set<QualifiedObjectName> unresolved,
                              List<String> warnings) {
        this(resolved, unresolved, warnings, Map.of());
    }

    public static MetadataResolution empty() {
        return new MetadataResolution(Map.of(), Set.of(), List.of(), Map.of());
    }

    public static MetadataResolution of(Map<QualifiedObjectName, SchemaTable> resolved,
                                        Set<QualifiedObjectName> unresolved) {
        return new MetadataResolution(resolved, unresolved, List.of(), Map.of());
    }

    public static MetadataResolution of(Map<QualifiedObjectName, SchemaTable> resolved,
                                        Set<QualifiedObjectName> unresolved,
                                        Map<QualifiedObjectName, TableDescriptor> descriptors) {
        return new MetadataResolution(resolved, unresolved, List.of(), descriptors);
    }

    public boolean isEmpty() {
        return resolved.isEmpty();
    }

    /**
     * 与后一个 provider 的结果合并：本结果已解析的表优先保留，
     * 未解析的交由后者补齐。用于 {@link CompositeMetadataProvider} 的优先级串联。
     */
    public MetadataResolution mergeWith(MetadataResolution next) {
        Map<QualifiedObjectName, SchemaTable> merged = new LinkedHashMap<>(this.resolved);
        next.resolved().forEach(merged::putIfAbsent);

        Set<QualifiedObjectName> stillUnresolved = new LinkedHashSet<>(this.unresolved);
        stillUnresolved.addAll(next.unresolved());
        stillUnresolved.removeAll(merged.keySet());

        List<String> allWarnings = new ArrayList<>(this.warnings);
        allWarnings.addAll(next.warnings());

        // 描述与结构同一套优先级：先解析出结构的那个来源赢。
        // 不做「A 出结构、B 出描述」的跨来源拼接 —— 那样拼出来的中文名可能来自
        // 另一个库里的同名表，比没有更糟。
        Map<QualifiedObjectName, TableDescriptor> mergedDescriptors =
                new LinkedHashMap<>(this.descriptors);
        next.descriptors().forEach(mergedDescriptors::putIfAbsent);

        return new MetadataResolution(
                Collections.unmodifiableMap(merged),
                Collections.unmodifiableSet(stillUnresolved),
                Collections.unmodifiableList(allWarnings),
                Collections.unmodifiableMap(mergedDescriptors));
    }
}
