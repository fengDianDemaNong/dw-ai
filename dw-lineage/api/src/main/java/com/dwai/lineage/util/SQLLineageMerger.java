package com.dwai.lineage.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dwai.lineage.dto.FailedStatement;
import com.dwai.lineage.dto.LineageGraph;
import com.dwai.lineage.service.metadata.CatalogPolicy;
import com.dwai.lineage.service.metadata.TableNameNormalizer;
import com.dwai.lineage.service.metadata.TempTableFilter;
import com.dwai.lineage.service.metadata.TempTableMatcher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 把 sqlflow 产出的多条 {@code Output} JSON 合并成前端可直接渲染的血缘图。
 *
 * <p>输出结构（保持与前端既有契约兼容，新增 {@code warnings} 与边上的 {@code cyclic} 标记）：
 * <pre>
 * {
 *   "code": 0,
 *   "data": {
 *     "withProcessData": { "data": [...], "size": n, "level": maxLevel },  // 含中间过程
 *     "noProcessData":   { "data": [...], "size": n, "level": maxLevel }   // 压平到根源
 *   },
 *   "warnings": ["..."],
 *   "errno": 0, "error": "", "request_id": "..."
 * }
 * </pre>
 *
 * <p>层级语义：最终输出字段为 0，每向上游一跳 +1，取<b>最长路径</b>。
 */
public final class SQLLineageMerger {

    private SQLLineageMerger() {
    }

    // ==================================================================
    // 对外入口
    // ==================================================================

    public static String mergeSQLLineage(List<String> lineageStrings) {
        return mergeSQLLineage(lineageStrings, null);
    }

    /** 序列化用的共享 mapper，仅供返回 JSON 字符串的兼容方法使用。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param lineageStrings sqlflow 每条语句产出的 Output JSON
     * @param columnFilter   可选。支持裸列名（{@code id}）或全限定名（{@code schema.table.id}）；
     *                       为空表示不过滤
     */
    public static String mergeSQLLineage(List<String> lineageStrings, String columnFilter) {
        return mergeSQLLineage(lineageStrings, columnFilter, Diagnostics.empty());
    }

    /**
     * 兼容旧调用方：返回序列化后的 JSON 字符串。
     * 新代码应直接使用 {@link #buildGraph}，由 Spring 负责序列化。
     */
    public static String mergeSQLLineage(List<String> lineageStrings,
                                         String columnFilter,
                                         Diagnostics diagnostics) {
        try {
            return JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(buildGraph(lineageStrings, columnFilter, diagnostics));
        } catch (Exception e) {
            throw new IllegalStateException("血缘结果序列化失败", e);
        }
    }

    /**
     * 合并成血缘图领域对象。
     *
     * @param diagnostics 解析过程中的诊断信息（失败语句、未解析的表、告警），
     *                    随结果一并返回，避免用户面对空图无从排查
     */
    public static LineageGraph buildGraph(List<String> lineageStrings,
                                          String columnFilter,
                                          Diagnostics diagnostics) {
        return buildGraph(lineageStrings, columnFilter, diagnostics, null);
    }

    /**
     * @param policy 数据目录策略：默认目录名用来把两段表名补成三段，临时规则用来穿透中间表。
     *               传 {@code null} 等同于 {@link CatalogPolicy#none()}，两样都不做
     */
    public static LineageGraph buildGraph(List<String> lineageStrings,
                                          String columnFilter,
                                          Diagnostics diagnostics,
                                          CatalogPolicy policy) {
        List<String> warnings = new ArrayList<>(diagnostics.warnings());

        LineageIndex index = parseLineageStrings(lineageStrings, JSON, warnings);
        if (index.isEmpty()) {
            warnings.add(emptyReason(diagnostics));
            return render(LineageIndex.empty(), warnings, diagnostics);
        }

        // 补目录必须排在临时过滤之前：TempTableMatcher 取倒数第三段当数据目录，
        // 两段名下那一段是空的，绑定了具体目录的规则会被静默跳过
        index = applyNormalize(index, policy);
        index = applyTempFilter(index, tempMatcherOf(policy), warnings);

        if (columnFilter != null && !columnFilter.isBlank()) {
            LineageIndex filtered = index.filterByColumn(columnFilter.trim());
            if (filtered.isEmpty()) {
                warnings.add("未匹配到列: " + columnFilter);
            }
            index = filtered;
        }

        return render(index, warnings, diagnostics);
    }

    /**
     * 按临时表规则穿透。
     *
     * <p>放在列过滤<b>之前</b>：先按 {@code tmp.bb} 过滤列再穿透，会把要桥接的中间节点
     * 提前摘掉，桥接就断了。而且用户想看的是「过滤后的图里的这一列」。
     *
     * <p>过滤掉了就在 warnings 里说一声。图上凭空少几个节点而没有任何提示，
     * 用户只会以为血缘解析错了。
     */
    /** 补齐数据目录。没有默认目录（比如租户上下文缺失）时原样返回，不猜。 */
    private static LineageIndex applyNormalize(LineageIndex index, CatalogPolicy policy) {
        if (policy == null || !policy.hasDefaultCatalog()) {
            return index;
        }
        return index.normalize(policy.defaultCatalog());
    }

    private static TempTableMatcher tempMatcherOf(CatalogPolicy policy) {
        return policy == null ? null : policy.tempMatcher();
    }

    private static LineageIndex applyTempFilter(LineageIndex index, TempTableMatcher matcher,
                                                List<String> warnings) {
        if (matcher == null || !matcher.hasRules()) {
            return index;
        }
        List<String> removed = TempTableFilter.removedTables(index.edges, matcher);
        if (removed.isEmpty()) {
            return index;
        }
        warnings.add("已按临时表规则隐藏 " + removed.size() + " 张临时表: "
                + String.join(", ", removed.stream().distinct().sorted().limit(10).toList())
                + (removed.size() > 10 ? " 等" : ""));
        return index.filterTemp(matcher);
    }

    /**
     * 直接用「目标字段 → 上游字段」的边集构图。
     *
     * <p>供从数据库读出的血缘使用：{@link #buildGraph} 的入参是 sqlflow 解析器输出的
     * JSON 字符串，而查询已保存的血缘时手上只有边，没必要为了复用而把边反序列化成
     * 那种字符串再解析回来 —— 那层往返既脆弱又没有意义。
     *
     * <p>层级、同层序号、环检测这些布局计算全部走与解析路径<b>同一份</b> {@code render()}，
     * 所以「解析出来的图」与「从库里读出来的图」在前端看到的结构是一致的。
     *
     * @param edges 目标字段 -&gt; 上游字段集合，字段名均为 {@code schema.table.column}
     */
    public static LineageGraph buildGraphFromEdges(Map<String, Set<String>> edges,
                                                   String columnFilter,
                                                   Diagnostics diagnostics) {
        return buildGraphFromEdges(edges, columnFilter, diagnostics, null);
    }

    /**
     * @param policy 临时表规则；为空或无规则时不做过滤。
     *               查已保存的血缘时应当传 null —— 库里本来就没有临时表，
     *               再过滤一遍只会掩盖「保存时没过滤干净」这种问题。
     *               <b>目录也不在这里补</b>：库里的全名恒为三段，
     *               真出现两段名说明写入路径漏了，应该暴露而不是在读取时抹平
     */
    public static LineageGraph buildGraphFromEdges(Map<String, Set<String>> edges,
                                                   String columnFilter,
                                                   Diagnostics diagnostics,
                                                   CatalogPolicy policy) {
        List<String> warnings = new ArrayList<>(diagnostics.warnings());

        LineageIndex built = new LineageIndex();
        if (edges != null) {
            edges.forEach((target, sources) -> {
                built.addTarget(target);
                sources.forEach(source -> built.addEdge(target, source));
            });
        }
        LineageIndex index = built;

        if (index.isEmpty()) {
            warnings.add("没有查到血缘数据");
            return render(LineageIndex.empty(), warnings, diagnostics);
        }

        index = applyTempFilter(index, tempMatcherOf(policy), warnings);

        if (columnFilter != null && !columnFilter.isBlank()) {
            LineageIndex filtered = index.filterByColumn(columnFilter.trim());
            if (filtered.isEmpty()) {
                warnings.add("未匹配到列: " + columnFilter);
            }
            index = filtered;
        }

        return render(index, warnings, diagnostics);
    }

    /** 结果为空时，尽量说清到底是「没有可解析的语句」还是「语句都解析失败了」。 */
    private static String emptyReason(Diagnostics diagnostics) {
        if (!diagnostics.failedStatements().isEmpty()) {
            return "全部 " + diagnostics.failedStatements().size()
                    + " 条语句解析失败，详见 failedStatements";
        }
        if (!diagnostics.unresolvedTables().isEmpty()) {
            return "未能获取以下表的结构，无法解析血缘: " + diagnostics.unresolvedTables();
        }
        return "未识别到可解析的血缘信息（检查 SQL 中是否包含 insert / create table as select / merge 语句）";
    }

    /**
     * 随血缘结果一并返回的诊断信息。
     *
     * @param failedStatements 解析失败的语句及原因
     * @param unresolvedTables 未能获取结构的表，{@code select *} 等场景会因此缺列
     * @param warnings         其它告警，例如某个元数据来源不可用
     */
    public record Diagnostics(List<FailedStatement> failedStatements,
                              List<String> unresolvedTables,
                              List<String> warnings) {

        public Diagnostics {
            failedStatements = failedStatements == null ? List.of() : failedStatements;
            unresolvedTables = unresolvedTables == null ? List.of() : unresolvedTables;
            warnings = warnings == null ? List.of() : warnings;
        }

        public static Diagnostics empty() {
            return new Diagnostics(List.of(), List.of(), List.of());
        }
    }

    // ==================================================================
    // 解析
    // ==================================================================

    private static LineageIndex parseLineageStrings(List<String> lineageStrings,
                                                    ObjectMapper mapper,
                                                    List<String> warnings) {
        LineageIndex index = new LineageIndex();
        if (lineageStrings == null) {
            return index;
        }

        for (int i = 0; i < lineageStrings.size(); i++) {
            String lineageString = lineageStrings.get(i);
            if (lineageString == null || lineageString.isBlank()) {
                continue;
            }
            try {
                JsonNode root = mapper.readTree(lineageString);
                String schema = root.path("schema").asText();
                String table = root.path("table").asText();
                String fullTableName = schema + "." + table;

                for (JsonNode columnNode : root.path("columns")) {
                    String targetField = fullTableName + "." + columnNode.path("column").asText();
                    index.addTarget(targetField);

                    for (JsonNode sourceColumnNode : columnNode.path("sourceColumns")) {
                        String sourceField = sourceColumnNode.path("tableName").asText()
                                + "." + sourceColumnNode.path("columnName").asText();
                        // 合并而非覆盖：多条语句可能写入同一目标字段
                        // （UNION 拆成多条 insert、分区表多次 insert overwrite 等）
                        index.addEdge(targetField, sourceField);
                    }
                }
            } catch (Exception e) {
                // 单条语句解析失败不影响其余语句
                warnings.add("第 " + (i + 1) + " 条血缘结果解析失败: " + e.getMessage());
            }
        }
        return index;
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    private static LineageGraph render(LineageIndex index,
                                       List<String> warnings, Diagnostics diagnostics) {
        Set<String> cyclicNodes = index.findCyclicNodes();
        if (!cyclicNodes.isEmpty()) {
            warnings.add("检测到循环血缘依赖，相关字段: " + new TreeSet<>(cyclicNodes)
                    + "；环上的边已标记 cyclic，层级计算已跳过回边");
        }

        Map<String, Integer> levelMap = index.calculateLevels();
        Map<String, Integer> indexMap = index.assignTableIndexes(levelMap);
        Layout layout = new Layout(levelMap, indexMap, index, cyclicNodes);

        LineageGraph.Data data = new LineageGraph.Data(
                buildWithProcessData(index, layout),
                buildNoProcessData(index, layout));

        return new LineageGraph(
                0,
                data,
                List.copyOf(warnings),
                List.copyOf(diagnostics.failedStatements()),
                List.copyOf(diagnostics.unresolvedTables()),
                warnings.isEmpty() ? "" : warnings.get(0),
                UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    }

    /** 保留全部中间过程的血缘。 */
    private static LineageGraph.Section buildWithProcessData(LineageIndex index, Layout layout) {
        List<LineageGraph.Item> items = new ArrayList<>();

        for (String target : index.sortedTargets()) {
            List<LineageGraph.Field> refs = new ArrayList<>();
            for (String ref : index.sourcesOf(target)) {
                refs.add(layout.edgeField(target, ref));
            }
            items.add(new LineageGraph.Item(layout.field(target), refs));
        }

        return new LineageGraph.Section(items, items.size(), layout.maxLevel());
    }

    /** 压平中间过程，最终输出字段直连根源字段。 */
    private static LineageGraph.Section buildNoProcessData(LineageIndex index, Layout layout) {
        List<LineageGraph.Item> items = new ArrayList<>();
        int maxLevel = 0;

        for (String target : index.finalTargets()) {
            List<LineageGraph.Field> refs = new ArrayList<>();
            for (String root : index.findRootSources(target)) {
                LineageGraph.Field f = layout.field(root);
                // 压平视图里的上游一律是根源字段
                refs.add(new LineageGraph.Field(f.fieldName(), true, f.index(), f.level(), f.cyclic()));
            }
            LineageGraph.Field targetField = layout.field(target);
            maxLevel = Math.max(maxLevel, targetField.level());
            items.add(new LineageGraph.Item(targetField, refs));
        }

        return new LineageGraph.Section(items, items.size(), maxLevel);
    }

    // ==================================================================
    // 布局：把层级 / 同层序号 / final / cyclic 落到字段节点上
    // ==================================================================

    private record Layout(Map<String, Integer> levelMap,
                          Map<String, Integer> indexMap,
                          LineageIndex index,
                          Set<String> cyclicNodes) {

        LineageGraph.Field field(String name) {
            return new LineageGraph.Field(
                    name,
                    !index.hasSources(name),
                    indexMap.getOrDefault(name, 0),
                    levelMap.getOrDefault(name, 0),
                    null);
        }

        /** 引用边，额外标注这条边是否落在环上。 */
        LineageGraph.Field edgeField(String target, String source) {
            LineageGraph.Field f = field(source);
            boolean cyclic = cyclicNodes.contains(target) && cyclicNodes.contains(source);
            return cyclic
                    ? new LineageGraph.Field(f.fieldName(), f.isFinal(), f.index(), f.level(), true)
                    : f;
        }

        int maxLevel() {
            return levelMap.values().stream().max(Integer::compare).orElse(0);
        }
    }

    // ==================================================================
    // 血缘图
    // ==================================================================

    /**
     * 血缘有向图。边的方向是 <b>目标字段 -&gt; 上游字段</b>（数据流的反方向），
     * 与前端「左侧为最终产出、向右追溯上游」的渲染方向一致。
     */
    private static final class LineageIndex {

        /** 目标字段 -> 上游字段集合。LinkedHashSet 保证保序去重。 */
        private final Map<String, Set<String>> edges = new LinkedHashMap<>();
        /** 出现过的全部字段。 */
        private final Set<String> allFields = new LinkedHashSet<>();
        /** 作为写入目标出现过的字段。 */
        private final Set<String> targetFields = new LinkedHashSet<>();

        static LineageIndex empty() {
            return new LineageIndex();
        }

        boolean isEmpty() {
            return allFields.isEmpty();
        }

        void addTarget(String field) {
            allFields.add(field);
            targetFields.add(field);
        }

        void addEdge(String target, String source) {
            allFields.add(target);
            allFields.add(source);
            edges.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(source);
        }

        /**
         * 按临时表规则重建索引：临时目标丢掉，其余目标的上游穿透到最近的非临时字段。
         *
         * <p>重建而不是原地删：{@code allFields} / {@code targetFields} 与 {@code edges}
         * 三者必须自洽，逐个删容易漏掉其中之一，留下引用了不存在字段的边。
         */
        LineageIndex filterTemp(TempTableMatcher matcher) {
            Map<String, Set<String>> filtered = TempTableFilter.filter(edges, matcher);
            LineageIndex out = new LineageIndex();
            for (String target : targetFields) {
                String table = TempTableFilter.tableOf(target);
                if (table == null || !matcher.isTemp(table)) {
                    out.addTarget(target);
                }
            }
            filtered.forEach((target, sources) ->
                    sources.forEach(source -> out.addEdge(target, source)));
            return out;
        }

        /**
         * 按默认数据目录把两段表名补成三段，重建索引。
         *
         * <p>同样是重建而不是原地改：字段名是 {@code edges} 的 key、
         * 也是 {@code allFields} / {@code targetFields} 的元素，原地改必然漏掉一处。
         *
         * <p>补齐后两个原本不同的名字可能撞成同一个（SQL 里同时写了
         * {@code ods.orders} 和 {@code default.ods.orders}），
         * {@code addTarget} / {@code addEdge} 底层是 Set，天然合并。
         */
        LineageIndex normalize(String defaultCatalog) {
            LineageIndex out = new LineageIndex();
            targetFields.forEach(t ->
                    out.addTarget(TableNameNormalizer.normalizeField(t, defaultCatalog)));
            TableNameNormalizer.normalizeEdges(edges, defaultCatalog).forEach((target, sources) ->
                    sources.forEach(source -> out.addEdge(target, source)));
            // 没有任何边的孤立字段：上面两步都覆盖不到，单独补进去
            allFields.forEach(f ->
                    out.allFields.add(TableNameNormalizer.normalizeField(f, defaultCatalog)));
            return out;
        }

        boolean hasSources(String field) {
            Set<String> sources = edges.get(field);
            return sources != null && !sources.isEmpty();
        }

        Set<String> sourcesOf(String field) {
            return edges.getOrDefault(field, Collections.emptySet());
        }

        /** 目标字段，按名称排序以保证输出稳定可复现。 */
        List<String> sortedTargets() {
            return new ArrayList<>(new TreeSet<>(targetFields));
        }

        /** 最终产出字段：没有被任何其它字段引用为上游的目标字段。 */
        List<String> finalTargets() {
            Set<String> referenced = new HashSet<>();
            edges.values().forEach(referenced::addAll);

            Set<String> result = new TreeSet<>(targetFields);
            result.removeAll(referenced);
            // 全部字段互相引用（成环）时退化为全部目标字段，避免结果为空
            return new ArrayList<>(result.isEmpty() ? new TreeSet<>(targetFields) : result);
        }

        // -------------------- 层级：拓扑序上的最长路径 --------------------

        /**
         * 以最终产出字段为 0 层，沿上游方向逐跳递增，取最长路径。
         *
         * <p>使用 Kahn 拓扑排序按序松弛，保证层级能完整向下游传播，
         * 且结果与输入顺序无关。存在环时强制释放剩余节点以打破回边，不会死循环。
         */
        Map<String, Integer> calculateLevels() {
            Map<String, Integer> level = new HashMap<>();
            Map<String, Integer> indegree = new HashMap<>();
            for (String field : allFields) {
                level.put(field, 0);
                indegree.put(field, 0);
            }
            for (Set<String> sources : edges.values()) {
                for (String source : sources) {
                    indegree.merge(source, 1, Integer::sum);
                }
            }

            Deque<String> queue = new ArrayDeque<>();
            // 排序入队，保证遍历顺序确定，输出可复现
            for (String field : new TreeSet<>(allFields)) {
                if (indegree.get(field) == 0) {
                    queue.add(field);
                }
            }

            Set<String> settled = new HashSet<>();
            while (settled.size() < allFields.size()) {
                while (!queue.isEmpty()) {
                    String current = queue.poll();
                    if (!settled.add(current)) {
                        continue;
                    }
                    int nextLevel = level.get(current) + 1;
                    for (String source : sourcesOf(current)) {
                        if (level.get(source) < nextLevel) {
                            level.put(source, nextLevel);
                        }
                        if (indegree.merge(source, -1, Integer::sum) == 0) {
                            queue.add(source);
                        }
                    }
                }

                if (settled.size() < allFields.size()) {
                    // 剩余节点全部落在环里。强制释放一个（等价于断掉一条回边）后继续，
                    // 选择当前层级最大者，使层级尽量贴近真实深度。
                    String forced = null;
                    for (String field : new TreeSet<>(allFields)) {
                        if (settled.contains(field)) {
                            continue;
                        }
                        if (forced == null || level.get(field) > level.get(forced)) {
                            forced = field;
                        }
                    }
                    indegree.put(forced, 0);
                    queue.add(forced);
                }
            }
            return level;
        }

        // -------------------- 环检测（Tarjan SCC） --------------------

        /** 返回落在环上的字段：所在强连通分量大小 &gt; 1，或存在自环。 */
        Set<String> findCyclicNodes() {
            Tarjan tarjan = new Tarjan(this);
            return tarjan.run();
        }

        // -------------------- 同层序号 --------------------

        /**
         * 为每个字段分配同层内的表序号。同一张表的所有字段共享一个序号
         * （前端据此把它们渲染进同一个节点）。
         */
        Map<Integer, Map<String, Integer>> groupTablesByLevel(Map<String, Integer> levelMap) {
            Map<Integer, Set<String>> levelToTables = new HashMap<>();
            for (String field : allFields) {
                levelToTables
                        .computeIfAbsent(levelMap.getOrDefault(field, 0), k -> new TreeSet<>())
                        .add(tableOf(field));
            }

            Map<Integer, Map<String, Integer>> result = new HashMap<>();
            levelToTables.forEach((level, tables) -> {
                Map<String, Integer> indexes = new HashMap<>();
                int i = 0;
                for (String table : tables) {
                    indexes.put(table, i++);
                }
                result.put(level, indexes);
            });
            return result;
        }

        Map<String, Integer> assignTableIndexes(Map<String, Integer> levelMap) {
            Map<Integer, Map<String, Integer>> byLevel = groupTablesByLevel(levelMap);
            Map<String, Integer> fieldToIndex = new HashMap<>();
            for (String field : allFields) {
                int level = levelMap.getOrDefault(field, 0);
                fieldToIndex.put(field, byLevel.getOrDefault(level, Collections.emptyMap())
                        .getOrDefault(tableOf(field), 0));
            }
            return fieldToIndex;
        }

        // -------------------- 根源追溯 --------------------

        /** 沿上游一直走到没有上游为止，返回根源字段。已访问节点直接跳过，天然容忍环。 */
        Set<String> findRootSources(String field) {
            Set<String> roots = new TreeSet<>();
            Set<String> visited = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>();

            stack.push(field);
            visited.add(field);
            while (!stack.isEmpty()) {
                String current = stack.pop();
                if (!hasSources(current)) {
                    if (!current.equals(field)) {
                        roots.add(current);
                    }
                    continue;
                }
                for (String source : sourcesOf(current)) {
                    if (visited.add(source)) {
                        stack.push(source);
                    }
                }
            }
            return roots;
        }

        // -------------------- 列过滤 --------------------

        /**
         * 只保留与指定列相关的血缘边。
         *
         * @param columnFilter 裸列名（精确匹配字段名最后一段）或全限定名 {@code schema.table.column}
         */
        LineageIndex filterByColumn(String columnFilter) {
            boolean qualified = columnFilter.indexOf('.') >= 0;
            LineageIndex filtered = new LineageIndex();

            for (String target : targetFields) {
                boolean targetMatches = matches(target, columnFilter, qualified);
                Set<String> matchedSources = new LinkedHashSet<>();
                for (String source : sourcesOf(target)) {
                    if (targetMatches || matches(source, columnFilter, qualified)) {
                        matchedSources.add(source);
                    }
                }
                if (targetMatches || !matchedSources.isEmpty()) {
                    filtered.addTarget(target);
                    matchedSources.forEach(source -> filtered.addEdge(target, source));
                }
            }
            return filtered;
        }

        private static boolean matches(String field, String columnFilter, boolean qualified) {
            return qualified ? field.equalsIgnoreCase(columnFilter)
                    : columnOf(field).equalsIgnoreCase(columnFilter);
        }
    }

    // ==================================================================
    // 字段名工具：字段名形如 schema.table.column（catalog 存在时为 catalog.schema.table.column）
    // ==================================================================

    /** 取字段所属的表（去掉最后一段列名），而非 schema。 */
    static String tableOf(String field) {
        int i = field.lastIndexOf('.');
        return i < 0 ? field : field.substring(0, i);
    }

    /** 取列名（最后一段）。 */
    static String columnOf(String field) {
        int i = field.lastIndexOf('.');
        return i < 0 ? field : field.substring(i + 1);
    }

    // ==================================================================
    // Tarjan 强连通分量
    // ==================================================================

    private static final class Tarjan {
        private final LineageIndex graph;
        private final Map<String, Integer> discoveryIndex = new HashMap<>();
        private final Map<String, Integer> lowLink = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final Set<String> cyclic = new HashSet<>();
        private int counter;

        Tarjan(LineageIndex graph) {
            this.graph = graph;
        }

        Set<String> run() {
            for (String field : new TreeSet<>(graph.allFields)) {
                if (!discoveryIndex.containsKey(field)) {
                    strongConnect(field);
                }
            }
            return cyclic;
        }

        /** 迭代式实现，避免深血缘链导致栈溢出。 */
        private void strongConnect(String root) {
            Deque<Frame> frames = new ArrayDeque<>();
            frames.push(new Frame(root, new ArrayList<>(graph.sourcesOf(root))));
            visit(root);

            while (!frames.isEmpty()) {
                Frame frame = frames.peek();
                if (frame.cursor < frame.successors.size()) {
                    String successor = frame.successors.get(frame.cursor++);
                    if (!discoveryIndex.containsKey(successor)) {
                        visit(successor);
                        frames.push(new Frame(successor, new ArrayList<>(graph.sourcesOf(successor))));
                    } else if (onStack.contains(successor)) {
                        lowLink.put(frame.node, Math.min(lowLink.get(frame.node), discoveryIndex.get(successor)));
                    }
                    continue;
                }

                frames.pop();
                if (lowLink.get(frame.node).equals(discoveryIndex.get(frame.node))) {
                    collectComponent(frame.node);
                }
                if (!frames.isEmpty()) {
                    Frame parent = frames.peek();
                    lowLink.put(parent.node, Math.min(lowLink.get(parent.node), lowLink.get(frame.node)));
                }
            }
        }

        private void visit(String node) {
            discoveryIndex.put(node, counter);
            lowLink.put(node, counter);
            counter++;
            stack.push(node);
            onStack.add(node);
        }

        private void collectComponent(String root) {
            List<String> component = new ArrayList<>();
            String node;
            do {
                node = stack.pop();
                onStack.remove(node);
                component.add(node);
            } while (!node.equals(root));

            boolean isCycle = component.size() > 1
                    // 单节点自环
                    || graph.sourcesOf(root).contains(root);
            if (isCycle) {
                cyclic.addAll(component);
            }
        }

        private static final class Frame {
            final String node;
            final List<String> successors;
            int cursor;

            Frame(String node, List<String> successors) {
                this.node = node;
                this.successors = successors;
            }
        }
    }
}
