package com.dwai.lineage.service.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * 给<b>用户自己写的</b>正则加一道保险，防止灾难性回溯把线程钉死。
 *
 * <h2>为什么需要</h2>
 * 临时库表规则的正则是用户在页面上填的，而 Java 的 {@code Pattern} 用的是回溯引擎：
 * {@code (.*a){20}} 这类写法遇到匹配不上的输入，尝试次数随输入长度爆炸 ——
 * 实测 31 个字符就要 19 亿次字符读取、耗时 36 秒。
 * 这些规则会在<b>每次解析、对每张表名</b>各跑一遍 —— 一条写坏的规则足以让
 * 之后所有的血缘解析都卡住，而解析线程一旦卡住是停不下来的
 * （{@code Future.cancel} 打断不了正则引擎，和打断不了 ANTLR 是同一个道理）。
 *
 * <h2>怎么防</h2>
 * 静态分析判定不了一个正则会不会爆炸，所以改成<b>限制它能做多少事</b>：
 * 把输入包一层，数 {@code charAt} 的调用次数，超预算就中断。
 * 正常匹配一个几十字符的表名只需要几百次读取，而灾难性回溯瞬间就会撞上限。
 *
 * <p>两处用它：{@code TempTableMatcher} 在匹配时兜底（超限即判定不命中，
 * 不让一条规则拖垮整次解析），{@code DataCatalogServiceImpl} 在保存规则时先试跑一遍
 * （当场告诉用户这个正则有问题，而不是等到血缘变慢才发现）。
 */
public final class RegexGuard {

    private static final Logger logger = LoggerFactory.getLogger(RegexGuard.class);

    /**
     * 单次匹配允许的字符读取次数。
     *
     * <p>取值依据：库名/表名最长 256 字符，线性或平方级的正则读取量在万级以内。
     * 给到 10 万留足余量，同时又远低于会让人察觉到卡顿的量级。
     */
    private static final int STEP_LIMIT = 100_000;
    // 实测参照（JDK 的回溯引擎）：
    //   tmp_.*        匹配一个表名  → 几百次
    //   (x+x+)+y      24 字符输入   → 4 千次
    //   (.*a){20}     31 字符输入   → 19 亿次、36 秒
    // 也就是说正常规则离上限有两个数量级的余量，而真出问题的会瞬间撞上来。
    // 顺带一提 (a+)+$ 在 Java 里并不爆炸（引擎有优化），别拿它当反例。

    /**
     * 试跑用的输入。
     *
     * <p>用多个形态而不是一个：能不能触发爆炸取决于输入长什么样。
     * 实测 {@code (.*a){20}} 要靠「一长串重复字符 + 一个匹配不上的结尾」才炸得出来，
     * 而 {@code (x+x+)+y} 要的是另一种。多试几个能覆盖常见形状。
     */
    private static final String[] PROBES = {
            "a".repeat(64) + "!",
            "x".repeat(64) + "!",
            "ab".repeat(32) + "!",
    };

    private RegexGuard() {
    }

    /**
     * 受保护的整串匹配。
     *
     * @return 是否匹配；超出步数预算时返回 {@code false} 并记一条 warn
     */
    public static boolean matches(Pattern pattern, String input, String rawPattern) {
        if (input == null) {
            return false;
        }
        try {
            return pattern.matcher(new BoundedCharSequence(input, STEP_LIMIT)).matches();
        } catch (StepLimitExceeded e) {
            // 判定为不命中而不是抛出去：一条规则再糟也不该让整次血缘解析失败。
            // 但必须留下痕迹，否则用户只会看到「规则怎么不生效」
            logger.warn("临时表规则的正则回溯过度，已放弃本次匹配（视为不命中）: pattern={}, input={}",
                    rawPattern, input);
            return false;
        }
    }

    /**
     * 保存规则前试跑一遍，判断这个正则会不会灾难性回溯。
     *
     * <p><b>这是尽力而为，不是保证</b>：一个正则会不会爆炸取决于输入，
     * 静态判定本身是不可判定问题，固定几个探测串只能覆盖常见形状。
     * 真正的兜底是 {@link #matches} 里的运行时预算 —— 这里只是为了能<b>当场</b>
     * 告诉用户，而不是让他等到血缘解析变慢才发现。
     *
     * @return true 表示危险
     */
    public static boolean isCatastrophic(Pattern pattern) {
        for (String probe : PROBES) {
            try {
                pattern.matcher(new BoundedCharSequence(probe, STEP_LIMIT)).matches();
            } catch (StepLimitExceeded e) {
                return true;
            }
        }
        return false;
    }

    /** 超出步数预算。故意继承 {@link RuntimeException} 以便穿过正则引擎的调用栈。 */
    private static final class StepLimitExceeded extends RuntimeException {
        StepLimitExceeded() {
            // 不需要栈：这不是用来排查的异常，构造栈只是浪费
            super(null, null, false, false);
        }
    }

    /**
     * 数着 {@code charAt} 次数的输入包装。
     *
     * <p>回溯引擎每试一个分支都要重读字符，所以读取次数正比于它做的工作量 ——
     * 这是在不改动正则引擎的前提下唯一能观测到「它在空转」的地方。
     */
    private static final class BoundedCharSequence implements CharSequence {

        private final CharSequence delegate;
        private final int limit;
        private int reads;

        BoundedCharSequence(CharSequence delegate, int limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (++reads > limit) {
                throw new StepLimitExceeded();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return delegate.subSequence(start, end);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
