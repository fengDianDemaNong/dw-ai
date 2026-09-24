package com.dwai.lineage.service.metadata.provider;

import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按优先级串联多个 provider：前一个解析不到的表，才交给后一个。
 *
 * <p>典型顺序为 <b>SQL 内 DDL &gt; 外部元数据服务 &gt; 从 SQL 推断</b>：
 * 脚本里显式写了建表语句就以它为准，其次查元数据服务，
 * 都拿不到时退化为从 SQL 结构推断，尽量给出部分血缘而不是直接失败。
 */
public class CompositeMetadataProvider implements MetadataProvider {

    private static final Logger logger = LoggerFactory.getLogger(CompositeMetadataProvider.class);

    private final List<MetadataProvider> delegates;

    public CompositeMetadataProvider(List<MetadataProvider> delegates) {
        this.delegates = delegates == null ? List.of() : List.copyOf(delegates);
    }

    @Override
    public String name() {
        return delegates.stream().map(MetadataProvider::name).collect(Collectors.joining(" > "));
    }

    @Override
    public MetadataResolution resolve(Set<QualifiedObjectName> tables) {
        if (tables == null || tables.isEmpty()) {
            return MetadataResolution.empty();
        }

        MetadataResolution accumulated = MetadataResolution.empty();
        Set<QualifiedObjectName> remaining = new LinkedHashSet<>(tables);

        for (MetadataProvider delegate : delegates) {
            if (remaining.isEmpty()) {
                break;
            }
            MetadataResolution current;
            try {
                current = delegate.resolve(remaining);
            } catch (Exception e) {
                // 某个来源整体不可用（服务宕机、配置错误）时降级到下一个，不中断整体解析
                logger.warn("元数据来源 {} 解析失败，降级到下一个: {}", delegate.name(), e.getMessage(), e);
                accumulated = accumulated.mergeWith(new MetadataResolution(
                        java.util.Map.of(), java.util.Set.of(),
                        List.of("元数据来源 " + delegate.name() + " 不可用: " + e.getMessage())));
                continue;
            }

            accumulated = accumulated.mergeWith(current);
            remaining.removeAll(current.resolved().keySet());
        }

        // 所有来源都跑完仍未解析的，如实标记
        if (!remaining.isEmpty()) {
            accumulated = accumulated.mergeWith(
                    new MetadataResolution(java.util.Map.of(), remaining, List.of()));
        }
        return accumulated;
    }
}
