package com.dwai.lineage.cli;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@code sql-cli} 的纯逻辑部分。
 *
 * <p>这个命令行工具由 {@code release/bin/sql-cli.sh} 调起，一直没有任何测试。
 * 连库、跑语句那部分依赖真实数据库，这里覆盖的是不依赖环境、
 * 但坏了会直接出事的三块：密码脱敏、参数解析、中文对齐。
 */
public class SqlCliTest {

    // ==================================================================
    // 密码脱敏
    // ==================================================================

    /**
     * 连接串会被打印到终端，密码必须抹掉。
     *
     * <p>这条坏了不会有任何报错，只会让密码静静地出现在终端和运维的日志里。
     */
    @Test
    public void passwordInUrlIsMasked() {
        assertEquals("jdbc:mysql://h/db?user=root&password=***",
                SqlCli.mask("jdbc:mysql://h/db?user=root&password=s3cret"));
    }

    @Test
    public void maskingIsCaseInsensitiveAndStopsAtDelimiters() {
        assertEquals("jdbc:x://h?PASSWORD=***&ssl=true",
                SqlCli.mask("jdbc:x://h?PASSWORD=hunter2&ssl=true"));
        assertEquals("jdbc:x://h;password=***;db=x",
                SqlCli.mask("jdbc:x://h;password=hunter2;db=x"));
    }

    @Test
    public void urlWithoutPasswordIsUntouched() {
        String url = "jdbc:h2:file:./data/dw_lineage";
        assertEquals(url, SqlCli.mask(url));
    }

    /** 脱敏后不能还残留原密码 —— 用一个不会被正则边界放过的值验一遍。 */
    @Test
    public void maskedUrlNeverContainsTheSecret() {
        String masked = SqlCli.mask("jdbc:pg://h/db?password=p@ss=w0rd&x=1");
        assertFalse("脱敏后仍能看到密码: " + masked, masked.contains("p@ss"));
    }

    // ==================================================================
    // 参数解析
    // ==================================================================

    @Test
    public void parsesLongAndShortForms() {
        SqlCli.Options o = SqlCli.Options.parse(new String[]{
                "-u", "jdbc:h2:mem:x", "--username", "sa", "-p", "pw", "-e", "select 1", "-v"});

        assertEquals("jdbc:h2:mem:x", o.url);
        assertEquals("sa", o.username);
        assertEquals("pw", o.password);
        assertEquals("select 1", o.execute);
        assertTrue(o.verbose);
        assertFalse(o.force);
    }

    /** {@code -e} 与 {@code -f} 语义冲突，必须在连库之前就拒绝。 */
    @Test
    public void executeAndFileAreMutuallyExclusive() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlCli.Options.parse(new String[]{"-e", "select 1", "-f", "a.sql"}));
        assertTrue(e.getMessage().contains("不能同时使用"));
    }

    /** 选项后面缺值时要报清楚是哪个选项，而不是数组越界。 */
    @Test
    public void missingOptionValueIsReportedWithTheOptionName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlCli.Options.parse(new String[]{"-u"}));
        assertTrue("错误信息要指明是哪个选项: " + e.getMessage(), e.getMessage().contains("-u"));
    }

    @Test
    public void unknownOptionIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlCli.Options.parse(new String[]{"--nope"}));
    }

    /**
     * 只给 {@code --help} 时不该因为缺其它参数而报错。
     *
     * <p>注：{@code parse} 在「有 url 有用户名但没密码」时会去读 {@code System.console()}
     * 交互式要密码。测试进程下 {@code System.console()} 为 null，那段会跳过，
     * 所以这里不会卡住。
     */
    @Test
    public void helpAloneParsesCleanly() {
        SqlCli.Options o = SqlCli.Options.parse(new String[]{"--help"});
        assertTrue(o.help);
        assertEquals(null, o.url);
    }

    // ==================================================================
    // 中文对齐
    // ==================================================================

    /**
     * 表格对齐按<b>显示宽度</b>算，不是字符数。
     *
     * <p>中文占两列，按 {@code String.length()} 排版的话，
     * 只要结果里有中文，整张表的竖线就会参差不齐 —— 而这个工具的输出全是中文表名。
     */
    @Test
    public void chineseCharactersCountAsTwoColumns() {
        assertEquals(4, SqlCli.displayWidth("订单"));
        assertEquals(2, SqlCli.displayWidth("ab"));
        assertEquals(6, SqlCli.displayWidth("ab订单"));
    }

    @Test
    public void emptyAndNullAreZeroWidth() {
        assertEquals(0, SqlCli.displayWidth(""));
        assertEquals(0, SqlCli.displayWidth(null));
    }
}
