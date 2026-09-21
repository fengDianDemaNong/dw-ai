package com.dwai.lineage.service.metadata;

import com.dwai.lineage.enums.MatchType;
import com.dwai.lineage.enums.TempRuleTarget;
import com.dwai.lineage.persistence.TempRuleRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 按配置的规则判断一张表是不是临时表。
 *
 * <p>ETL 的 SQL 为了完成任务会建一堆中间临时表，它们既不该淹没血缘图，
 * 也不该被存进库里。哪些算临时由用户配置（临时库 {@code tmp} / {@code test}、
 * 临时表 {@code tmp_*}），本类负责把规则应用到具体的表名上。
 *
 * <p>规则在构造时就编译好：过滤一张图要判断成百上千个表名，
 * 每次都重新编译正则太浪费。<b>正则写错的规则会被跳过而不是让整次解析失败</b> ——
 * 一条配错的规则不该让血缘功能整个不可用，代价是它静默失效，
 * 所以配置页面必须提供当场试的入口。
 */
public final class TempTableMatcher {

    /** 一条编译好的规则。 */
    private record Compiled(String catalogName, TempRuleTarget target, MatchType matchType,
                            Pattern pattern, String rawPattern, long ruleId) {
    }

    private final List<Compiled> rules;

    private TempTableMatcher(List<Compiled> rules) {
        this.rules = rules;
    }

    /** 没有任何规则时的匹配器：什么都不算临时。 */
    public static TempTableMatcher empty() {
        return new TempTableMatcher(List.of());
    }

    public static TempTableMatcher of(List<TempRuleRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return empty();
        }
        List<Compiled> compiled = new ArrayList<>();
        for (TempRuleRow row : rows) {
            if (!row.enabled()) {
                continue;
            }
            compile(row).ifPresent(compiled::add);
        }
        return new TempTableMatcher(compiled);
    }

    private static Optional<Compiled> compile(TempRuleRow row) {
        try {
            Pattern pattern = row.matchType() == MatchType.GLOB
                    ? Pattern.compile(globToRegex(row.pattern()), Pattern.CASE_INSENSITIVE)
                    : Pattern.compile(row.pattern(), Pattern.CASE_INSENSITIVE);
            return Optional.of(new Compiled(normalize(row.catalogName()), row.target(),
                    row.matchType(), pattern, row.pattern(), row.id()));
        } catch (PatternSyntaxException e) {
            // 一条规则写错不该让整个血缘功能挂掉
            return Optional.empty();
        }
    }

    public boolean hasRules() {
        return !rules.isEmpty();
    }

    /**
     * 判断表全名是否为临时表。
     *
     * @param tableFullName {@code catalog.schema.table} 或 {@code schema.table}
     */
    public boolean isTemp(String tableFullName) {
        return matchedRuleId(tableFullName).isPresent();
    }

    /**
     * 返回命中的规则 id，供配置页面的「测试」功能告诉用户是<b>哪一条</b>生效了。
     *
     * <p>只说「命中了」没用 —— 配了七八条规则时，用户需要知道是哪条，
     * 才能判断是不是自己想要的那条。
     */
    public Optional<Long> matchedRuleId(String tableFullName) {
        return match(tableFullName).map(Match::ruleId);
    }

    /** 命中的规则。{@code pattern} 是用户原样写的表达式，不是转换后的正则。 */
    public record Match(long ruleId, TempRuleTarget target, MatchType matchType, String pattern) {
    }

    /**
     * 返回命中的规则本身，供配置页的试算框展示。
     *
     * <p>规则按 id 顺序逐条试，命中即返回 —— 与库里的列表顺序一致，
     * 页面上高亮的那条就是实际生效的那条。
     */
    public Optional<Match> match(String tableFullName) {
        if (tableFullName == null || tableFullName.isBlank()) {
            return Optional.empty();
        }
        String[] parts = tableFullName.split("\\.");
        String catalog = parts.length >= 3 ? parts[parts.length - 3] : null;
        String schema = parts.length >= 2 ? parts[parts.length - 2] : null;
        String table = parts[parts.length - 1];

        for (Compiled rule : rules) {
            // catalog 为空的规则作用于该项目全部数据目录
            if (rule.catalogName() != null
                    && !rule.catalogName().equals(normalize(catalog))) {
                continue;
            }
            String candidate = rule.target() == TempRuleTarget.SCHEMA ? schema : table;
            // 走 RegexGuard 而不是直接 matches()：用户写的正则可能灾难性回溯，
            // 而这段代码对每张表名都要跑一遍，卡住一次就是整次解析卡住
            if (RegexGuard.matches(rule.pattern(), candidate, rule.rawPattern())) {
                return Optional.of(new Match(rule.ruleId(), rule.target(),
                        rule.matchType(), rule.rawPattern()));
            }
        }
        return Optional.empty();
    }

    /**
     * 通配符转正则：{@code *} → {@code .*}，{@code ?} → {@code .}，其余字符按字面处理。
     *
     * <p>逐字符转义而不是先 {@link Pattern#quote} 再替换：{@code quote} 产生的
     * {@code \Q...\E} 包裹会把里面的 {@code .*} 也当成字面量，替换就白做了。
     */
    static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder(glob.length() * 2);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> out.append(".*");
                case '?' -> out.append('.');
                // 正则元字符按字面转义，否则用户写的 tmp.bak 会把点当成任意字符
                case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' ->
                        out.append('\\').append(c);
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
