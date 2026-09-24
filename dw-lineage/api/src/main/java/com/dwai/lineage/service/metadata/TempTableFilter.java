package com.dwai.lineage.service.metadata;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把临时表从血缘边表里<b>穿透掉</b>。
 *
 * <p>注意是穿透而不是删点。给定
 *
 * <pre>
 * create tmp.bb as select * from source_a;
 * insert into sink_b select * from tmp.bb;
 * </pre>
 *
 * 解析出 {@code source_a → tmp.bb → sink_b}，过滤后要得到 {@code source_a → sink_b}，
 * 而不是把 {@code tmp.bb} 删掉留下两段互不相连的图 —— 那样等于把血缘打断，
 * 比不过滤还糟。
 *
 * <p>链式临时表 {@code A → t1 → t2 → B} 要一路穿到底。临时节点之间成环时
 * （{@code t1 → t2 → t1}）靠 visited 集合停下来，不会无限递归。
 *
 * <p>边表的方向：key 是下游字段，value 是它的上游字段集合。
 */
public final class TempTableFilter {

    private TempTableFilter() {
    }

    /**
     * @param edges   下游字段 → 上游字段集合
     * @param matcher 判断表是否临时
     * @return 过滤后的边表；没有规则或没命中时原样返回
     */
    public static Map<String, Set<String>> filter(Map<String, Set<String>> edges,
                                                  TempTableMatcher matcher) {
        if (edges == null || edges.isEmpty() || matcher == null || !matcher.hasRules()) {
            return edges;
        }

        Map<String, Boolean> tempCache = new LinkedHashMap<>();
        Map<String, Set<String>> result = new LinkedHashMap<>();

        for (Map.Entry<String, Set<String>> entry : edges.entrySet()) {
            String target = entry.getKey();
            // 下游本身是临时表：整条边丢掉。它的上游会在别人穿透它时被接上
            if (isTemp(target, matcher, tempCache)) {
                continue;
            }
            Set<String> sources = resolveSources(entry.getKey(), edges, matcher, tempCache);
            if (!sources.isEmpty()) {
                result.put(target, sources);
            }
        }
        return result;
    }

    /**
     * 逐层向上找，直到找到非临时的上游。
     *
     * <p>广度优先而不是递归：临时表链条可能很长，而且成环时递归会栈溢出 ——
     * 环在 ETL 脚本里不算罕见（反复写同一张中转表）。
     */
    private static Set<String> resolveSources(String target, Map<String, Set<String>> edges,
                                              TempTableMatcher matcher,
                                              Map<String, Boolean> tempCache) {
        Set<String> resolved = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(edges.getOrDefault(target, Set.of()));

        while (!queue.isEmpty()) {
            String source = queue.poll();
            if (!visited.add(source)) {
                // 已经走过，说明有环；不再展开，避免死循环
                continue;
            }
            if (!isTemp(source, matcher, tempCache)) {
                resolved.add(source);
                continue;
            }
            // 临时字段：换成它的上游继续往上找
            queue.addAll(edges.getOrDefault(source, Set.of()));
        }
        return resolved;
    }

    /** 字段全名 → 所属表全名 → 是否临时。按表缓存，一张表的字段往往有几十个。 */
    private static boolean isTemp(String columnFullName, TempTableMatcher matcher,
                                  Map<String, Boolean> cache) {
        String table = tableOf(columnFullName);
        if (table == null) {
            return false;
        }
        return cache.computeIfAbsent(table, matcher::isTemp);
    }

    /** 去掉最后一段列名，得到表全名。 */
    public static String tableOf(String columnFullName) {
        if (columnFullName == null) {
            return null;
        }
        int dot = columnFullName.lastIndexOf('.');
        return dot <= 0 ? null : columnFullName.substring(0, dot);
    }

    /** 过滤后被穿透掉的表，供页面提示「已隐藏 N 张临时表」。 */
    public static List<String> removedTables(Map<String, Set<String>> edges,
                                             TempTableMatcher matcher) {
        if (edges == null || matcher == null || !matcher.hasRules()) {
            return List.of();
        }
        Set<String> tables = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> e : edges.entrySet()) {
            tables.add(tableOf(e.getKey()));
            e.getValue().forEach(v -> tables.add(tableOf(v)));
        }
        List<String> removed = new ArrayList<>();
        for (String table : tables) {
            if (table != null && matcher.isTemp(table)) {
                removed.add(table);
            }
        }
        return removed;
    }
}
