package metadata;

import org.junit.Test;
import com.dwai.lineage.service.metadata.RegexGuard;

import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 用户自定义正则的回溯保险。
 *
 * <p>临时库表规则的正则由用户在页面上填写，会在<b>每次解析、对每张表名</b>各跑一遍。
 * Java 的正则是回溯引擎，一条写坏的规则足以让之后所有血缘解析卡住 ——
 * 而卡住的解析线程是打断不掉的。
 *
 * <p><b>这个测试本身必须能在几秒内跑完</b>：如果保险失效，下面这些用例就会挂住，
 * 那也正是它该有的失败方式（超时比断言失败更能说明问题）。
 */
public class RegexGuardTest {

    /**
     * 实测在 JDK 回溯引擎上真会爆炸的写法：31 字符输入需要 19 亿次字符读取、36 秒。
     *
     * <p>没有用教科书上的 {@code (a+)+$} —— 实测 Java 对它有优化，读取次数只是
     * O(n²)（32 字符 624 次），拿它当反例会得到一个永远绿的假测试。
     */
    private static final Pattern CATASTROPHIC = Pattern.compile("(.*a){20}");

    @Test(timeout = 5_000)
    public void catastrophicPatternIsAbortedInsteadOfHanging() {
        String evil = "a".repeat(30) + "b";

        // 没有保险的话这一句要跑到宇宙热寂
        assertFalse("回溯超预算时应判定不命中，而不是卡住",
                RegexGuard.matches(CATASTROPHIC, evil, "(.*a){20}"));
    }

    @Test(timeout = 5_000)
    public void catastrophicPatternIsDetectedUpFront() {
        assertTrue("保存规则时就该识别出这个正则有问题",
                RegexGuard.isCatastrophic(CATASTROPHIC));
    }

    // ==================================================================
    // 正常的正则不能被误伤
    // ==================================================================

    @Test
    public void ordinaryPatternsStillMatch() {
        assertTrue(RegexGuard.matches(Pattern.compile("tmp_.*"), "tmp_orders", "tmp_.*"));
        assertTrue(RegexGuard.matches(Pattern.compile("(?i)TMP"), "tmp", "(?i)TMP"));
    }

    @Test
    public void ordinaryPatternsStillReject() {
        assertFalse(RegexGuard.matches(Pattern.compile("tmp_.*"), "ods_orders", "tmp_.*"));
        // matches() 是整串匹配，前缀命中不算
        assertFalse(RegexGuard.matches(Pattern.compile("tmp"), "tmp_orders", "tmp"));
    }

    @Test
    public void ordinaryPatternsAreNotFlaggedAsCatastrophic() {
        for (String p : new String[]{"tmp_.*", "^(tmp|test)_\\w+$", ".*_bak", "[a-z]+_\\d{8}"}) {
            assertFalse("正常正则不该被当成危险的: " + p,
                    RegexGuard.isCatastrophic(Pattern.compile(p)));
        }
    }

    /** 通配符翻译出来的正则（{@code tmp_*} → {@code tmp_.*}）也要安全通过。 */
    @Test
    public void globTranslatedPatternsAreSafe() {
        assertFalse(RegexGuard.isCatastrophic(Pattern.compile("tmp_.*", Pattern.CASE_INSENSITIVE)));
        assertTrue(RegexGuard.matches(
                Pattern.compile("tmp_.*", Pattern.CASE_INSENSITIVE), "TMP_ABC", "tmp_*"));
    }

    @Test
    public void nullInputNeverMatches() {
        assertFalse(RegexGuard.matches(Pattern.compile(".*"), null, ".*"));
    }
}
