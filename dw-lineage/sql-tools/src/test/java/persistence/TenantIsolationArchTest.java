package persistence;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 架构约束：{@code persistence} 包里的每一条原生 SQL 都必须带 {@code tenant_id} 过滤。
 *
 * <p>为什么需要它：多租户隔离是靠「每个仓储方法首参传 {@link com.dwai.lineage.tenant.LineageContext}
 * 且 SQL 里带上它」来保证的。这是一条<b>约定</b>，编译器管不着 ——
 * 新写一个查询时漏掉 {@code where tenant_id = ?}，跨租户就能读到别人的数据，
 * 而功能测试全都会通过，因为单租户场景下结果一模一样。
 *
 * <p>{@code LineageRepository} 的注释里一直写着「由 TenantIsolationArchTest 做静态检查兜底」，
 * 但这个类此前并不存在。现在补上。
 *
 * <h2>检查方式</h2>
 * 读源码而不是用字节码扫描：SQL 是拼出来的字符串，编译后已经拆散，源码层面反而好判断。
 * 以 Java 语句（顶层分号）为单位，把语句里的所有字符串字面量拼起来，
 * 凡是拼出来像 SQL 的（以 select/insert/update/delete 开头），就要求含 {@code tenant_id}。
 * 按语句而非按字面量聚合，是因为代码里普遍这样拼：
 * {@code "select id from " + table + " where tenant_id = ?"}。
 */
public class TenantIsolationArchTest {

    private static final Path PERSISTENCE_DIR =
            Path.of("src", "main", "java", "com", "dwai", "lineage", "persistence");

    /**
     * 这些表不属于业务数据，本身不带租户维度。
     *
     * <p>{@code tenant} 表是租户的<b>定义者</b>，它没有也不该有 {@code tenant_id} 列，
     * 所以 {@code JdbcTenantAdminRepository} 里操作它的 SQL 必须放行。
     *
     * <p>⚠️ 放行方式是「看这条 SQL 的主表是不是它」，<b>不是</b>子串包含。
     * 如果写成 {@code sql.contains("tenant")}，那么每一条含 {@code tenant_id} 的 SQL
     * 都会命中而被整体豁免 —— 护栏当场失效，而测试仍然全绿，
     * 正好是这个测试当初要防的那类事故。{@link #exemptionIsNarrow()} 钉住这一点。
     */
    private static final List<String> TENANT_FREE_TABLES = List.of("flyway_schema_history", "tenant");

    /**
     * 逐条豁免的 SQL。
     *
     * <p>与 {@link #TENANT_FREE_TABLES} 的按表豁免不同，这里钉的是<b>具体某一条语句</b> ——
     * 同一张表的其它 SQL 仍然必须带租户过滤。
     *
     * <p>匹配方式是「包含这段文本」。语句一旦被改动，豁免就不再命中、测试立刻变红，
     * 逼着人重新确认它是否仍然应该豁免 —— 这正是我们要的失败方式。
     */
    private static final List<String> TENANT_FREE_STATEMENTS = List.of(
            // 服务启动时把上次残留的 RUNNING/PENDING 任务标成 FAILED。
            // 这是一次性清理，必须覆盖所有租户；启动阶段也没有任何请求上下文可用。
            "update sync_job set status = ?, message = ?, finished_at = ?, updated_at = ? "
                    + "where status in (?, ?)");

    /*
     * ⚠️ 这个守卫的已知盲区，别把它当成万无一失。
     *
     * 判据是「整条 SQL 里出现过 tenant_id」，所以<b>多表语句只要有一处带上就算过</b>。
     * 子查询里 join 了另一张业务表却没带租户条件，检查不出来 —— 概览统计的
     * isolatedTables 就这么漏过一次（已修）。
     *
     * 为什么不收紧成「每张被引用的表都要有自己的 tenant_id」：项目里 12 处 join
     * 大多是「外层表已按租户过滤，内层靠外键关联」的合法写法，
     * 例如 `from lineage_column c join lineage_table t on t.id = c.table_id
     * where c.tenant_id = ?` —— t 由 c 约束，本身不需要再写一遍。
     * 按那条规则会大面积误报，而一个天天误报的护栏很快就会被加白名单加到失效。
     *
     * 真要做到位得把 SQL 解析成语法树、判断每个表引用是否被租户条件可达 ——
     * 项目里确实有 SQL 解析器，但让它来审自己的 SQL 是另一个量级的工程。
     * 当前的取舍：守住「整条 SQL 一个租户条件都没有」这条底线（这是最常见的疏漏），
     * 子查询那一层靠 code review。
     */

    /** 取 SQL 的主表：第一个 {@code from/into/update} 后面跟的那个名字。 */
    private static final Pattern PRIMARY_TABLE =
            Pattern.compile("\\b(?:from|into|update)\\s+([a-z_][a-z0-9_]*)", Pattern.CASE_INSENSITIVE);

    @Test
    public void everyNativeSqlInPersistenceFiltersByTenant() throws IOException {
        List<Path> sources = listJavaFiles();
        assertTrue("没扫到任何源文件，说明路径不对，检查本身失效了", sources.size() >= 3);

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            for (String sql : findUnfilteredSql(Files.readString(source, StandardCharsets.UTF_8))) {
                violations.add(source.getFileName() + " → " + trim(sql));
            }
        }

        assertTrue("以下原生 SQL 没有带 tenant_id 过滤，会造成跨租户读写：\n  "
                + String.join("\n  ", violations), violations.isEmpty());
    }

    /**
     * 守住这个守卫本身。
     *
     * <p>一个永远不会失败的架构测试等于没有。这里用合成代码片段验证检查器确实能
     * 抓到漏掉的租户过滤，同时不会误伤正常写法 —— 特别是
     * {@code "..." + 变量 + "..."} 这种跨字面量拼接（代码里到处都是），
     * 按单个字面量判断的话会大面积误报。
     */
    @Test
    public void detectorCatchesViolationsAndAcceptsValidSql() {
        String missingFilter = """
                void bad() {
                    jdbc.query("select id, full_name from lineage_table where project_id = ?",
                            MAPPER, ctx.projectId());
                }
                """;
        assertEquals("漏掉 tenant_id 的 SQL 必须被抓到",
                1, findUnfilteredSql(missingFilter).size());

        String concatenatedButFiltered = """
                void good() {
                    jdbc.query("select id from " + table
                            + " where tenant_id = ? and project_id = ?",
                            MAPPER, ctx.tenantId(), ctx.projectId());
                }
                """;
        assertTrue("跨字面量拼接出的合法 SQL 不应误报",
                findUnfilteredSql(concatenatedButFiltered).isEmpty());

        String textBlockSql = """
                void alsoGood() {
                    String sql = \"""
                            SELECT c.id FROM lineage_column c
                             WHERE c.tenant_id = ? AND c.project_id = ?
                            \""";
                }
                """;
        assertTrue("文本块写法不应误报", findUnfilteredSql(textBlockSql).isEmpty());

        String commentOnly = """
                void none() {
                    // select * from something 这只是注释里的说明
                    return;
                }
                """;
        assertTrue("注释里的 SQL 不算数", findUnfilteredSql(commentOnly).isEmpty());
    }

    /**
     * 守住豁免口，别让它把整个护栏一起放掉。
     *
     * <p>为了让 {@code JdbcTenantAdminRepository} 能操作没有 {@code tenant_id} 列的
     * {@code tenant} 表，豁免名单里加了 {@code "tenant"}。这一步有个很容易踩的坑：
     * 只要判定方式退回成子串包含，{@code where tenant_id = ?} 里的 "tenant" 就会命中，
     * <b>所有</b>业务 SQL 都被豁免，护栏静默失效而测试照样绿。
     *
     * <p>下面四条断言把边界钉死。任何一条挂了，都说明豁免被写宽了。
     */
    @Test
    public void exemptionIsNarrow() {
        String tenantTableItself = """
                void ok() {
                    jdbc.update("delete from tenant where id = ?", id);
                }
                """;
        assertTrue("tenant 表自身没有 tenant_id 列，必须放行",
                findUnfilteredSql(tenantTableItself).isEmpty());

        // 最关键的一条：漏了过滤的业务 SQL 不能因为出现过 "tenant" 字样就被放行
        String businessSqlMentioningTenantWord = """
                void bad() {
                    jdbc.query("select id from lineage_table where project_id = ?", MAPPER, p);
                }
                """;
        assertEquals("业务表漏掉 tenant_id 仍必须被抓到",
                1, findUnfilteredSql(businessSqlMentioningTenantWord).size());

        String joinsTenantButIsBusinessQuery = """
                void alsoBad() {
                    jdbc.query("select lt.id from lineage_table lt join tenant t on t.id = lt.x", M);
                }
                """;
        assertEquals("主表是业务表时，join 了 tenant 也不能豁免",
                1, findUnfilteredSql(joinsTenantButIsBusinessQuery).size());

        // 逐条豁免不能扩大化：改一个字就不该再命中
        String nearMiss = """
                void almost() {
                    jdbc.update("update sync_job set status = ?, message = ? where status in (?)");
                }
                """;
        assertEquals("与豁免名单不完全一致的语句仍必须被抓到",
                1, findUnfilteredSql(nearMiss).size());

        String projectTableStillFiltered = """
                void projectMustFilter() {
                    jdbc.update("delete from project where id = ?", id);
                }
                """;
        assertEquals("project 表有 tenant_id 列，不在豁免名单里",
                1, findUnfilteredSql(projectTableStillFiltered).size());
    }

    // ------------------------------------------------------------------

    /** 返回该段源码里所有「像 SQL 但没带 tenant_id」的语句。 */
    private static List<String> findUnfilteredSql(String rawCode) {
        List<String> found = new ArrayList<>();
        String code = stripComments(rawCode);
        for (String statement : splitJavaStatements(code)) {
            String sql = concatStringLiterals(statement);
            if (!looksLikeSql(sql) || isTenantFree(sql) || isExemptStatement(sql)) {
                continue;
            }
            if (!sql.toLowerCase(Locale.ROOT).contains("tenant_id")) {
                found.add(sql);
            }
        }
        return found;
    }

    private static List<Path> listJavaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(PERSISTENCE_DIR)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** 去掉行注释与块注释，避免注释里的中文说明被当成 SQL。字符串内的 // 要保留。 */
    static String stripComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        boolean inString = false;
        boolean inTextBlock = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);

            if (inTextBlock) {
                out.append(c);
                if (c == '"' && code.startsWith("\"\"\"", i)) {
                    out.append("\"\"");
                    i += 2;
                    inTextBlock = false;
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < code.length()) {
                    out.append(code.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (code.startsWith("\"\"\"", i)) {
                out.append("\"\"\"");
                i += 2;
                inTextBlock = true;
                continue;
            }
            if (c == '"') {
                out.append(c);
                inString = true;
                continue;
            }
            if (code.startsWith("//", i)) {
                while (i < code.length() && code.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
                continue;
            }
            if (code.startsWith("/*", i)) {
                int end = code.indexOf("*/", i + 2);
                i = end < 0 ? code.length() : end + 1;
                out.append('\n');
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 按顶层分号切成 Java 语句；字符串字面量里的分号不算。 */
    static List<String> splitJavaStatements(String code) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean inTextBlock = false;

        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            current.append(c);

            if (inTextBlock) {
                if (c == '"' && code.startsWith("\"\"\"", i)) {
                    current.append("\"\"");
                    i += 2;
                    inTextBlock = false;
                }
            } else if (inString) {
                if (c == '\\' && i + 1 < code.length()) {
                    current.append(code.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
            } else if (code.startsWith("\"\"\"", i)) {
                current.append("\"\"");
                i += 2;
                inTextBlock = true;
            } else if (c == '"') {
                inString = true;
            } else if (c == ';') {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        statements.add(current.toString());
        return statements;
    }

    /** 把一条 Java 语句里的全部字符串字面量首尾相接。 */
    static String concatStringLiterals(String statement) {
        StringBuilder sql = new StringBuilder();
        int i = 0;
        while (i < statement.length()) {
            if (statement.startsWith("\"\"\"", i)) {
                int end = statement.indexOf("\"\"\"", i + 3);
                if (end < 0) {
                    break;
                }
                sql.append(statement, i + 3, end);
                i = end + 3;
                continue;
            }
            if (statement.charAt(i) == '"') {
                int j = i + 1;
                while (j < statement.length() && statement.charAt(j) != '"') {
                    if (statement.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                sql.append(statement, i + 1, Math.min(j, statement.length()));
                i = j + 1;
                continue;
            }
            i++;
        }
        return sql.toString();
    }

    private static boolean looksLikeSql(String sql) {
        String head = sql.stripLeading().toLowerCase(Locale.ROOT);
        return head.startsWith("select ")
                || head.startsWith("insert into ")
                || head.startsWith("update ")
                || head.startsWith("delete from ")
                || head.startsWith("with recursive ");
    }

    /**
     * 该 SQL 的主表是否是不带租户维度的表。
     *
     * <p>只看主表，不看整条语句里出现过哪些名字：一条以 {@code lineage_table} 为主表、
     * 顺带 join 了 {@code tenant} 的查询，仍然必须带租户过滤。
     */
    /** 该语句是否在逐条豁免名单里。比对时把连续空白归一，免得换行/缩进的差异导致失配。 */
    private static boolean isExemptStatement(String sql) {
        String normalized = sql.replaceAll("\\s+", " ").trim();
        return TENANT_FREE_STATEMENTS.stream()
                .map(exempt -> exempt.replaceAll("\\s+", " ").trim())
                .anyMatch(normalized::contains);
    }

    private static boolean isTenantFree(String sql) {
        Matcher m = PRIMARY_TABLE.matcher(sql);
        if (!m.find()) {
            return false;
        }
        String table = m.group(1).toLowerCase(Locale.ROOT);
        return TENANT_FREE_TABLES.contains(table);
    }

    private static String trim(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
    }
}
