package metadata;

import org.junit.Test;
import com.dwai.lineage.service.metadata.MetadataServiceFactory;
import com.dwai.lineage.service.metadata.ParseCancellation;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 解析取消检查点。
 *
 * <p>钉两件事：中断标志置上后解析确实会中止；以及<b>没有中断时不影响正常解析</b> ——
 * 后者更要紧，这个检查点挂在每一条语法规则上，装错了会把所有解析都毁掉。
 */
public class ParseCancellationTest {

    private final MetadataServiceFactory factory = new MetadataServiceFactory();

    /**
     * 没有中断时，一切照常。
     *
     * <p>检查点装在 {@code addParseListener} 上，每退出一条规则回调一次。
     * 万一它误抛，受影响的是<b>全部</b> SQL 解析，所以这条是主用例。
     */
    @Test
    public void normalParsingIsUnaffected() {
        var statements = factory.statements("hive",
                "insert into dwd.detail select id, amount from ods.orders where dt = '2024-01-01'");
        assertTrue("正常 SQL 必须照常解析", statements.size() >= 1);
    }

    @Test
    public void repeatedParsingDoesNotAccumulateState() {
        // 计数器是 per-listener 的，而 listener 每次解析新建。
        // 若不慎写成静态计数，跑够 256 次之后就会开始误判
        for (int i = 0; i < 50; i++) {
            assertTrue(factory.statements("hive", "select " + i + " from ods.t").size() >= 1);
        }
    }

    /**
     * 线程被中断后，解析要能中止而不是跑到底。
     *
     * <p>在子线程里先置上中断标志再解析。检查点每 256 条规则查一次，
     * 所以 SQL 要有足够多的语法规则才会走到检查点 —— 用一串 union 撑规模。
     */
    @Test(timeout = 30_000)
    public void interruptedThreadAbortsParsing() throws Exception {
        StringBuilder sql = new StringBuilder("select a1 from ods.t1");
        for (int i = 2; i <= 300; i++) {
            sql.append(" union all select a").append(i).append(" from ods.t").append(i);
        }

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                // 必须走 analyze 而不是 statements：后者是 superior-sql-parser 那一段，
                // 它没有可注入的钩子，本来就打断不了。检查点在 sqlflow 那一段
                factory.analyze("hive", false, "insert into dwd.d " + sql, null);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        worker.start();
        worker.join(25_000);

        assertNotNull("解析没有因中断而中止 —— 检查点没起作用", thrown.get());
        assertTrue("中止的原因应当是取消，实际: " + thrown.get(),
                describe(thrown.get()).contains("取消") || describe(thrown.get()).contains("cancel"));
    }

    /** 装不上检查点时不能让解析失败 —— 那只是失去一个优化，不是功能故障。 */
    @Test
    public void installIsSafeOnAParserWithoutParseTree() {
        // 这里用一个真实的解析流程间接覆盖：install 内部对
        // UnsupportedOperationException 做了吞掉处理，装不上也不会影响下面这次解析
        assertNull(runQuietly(() -> factory.statements("hive", "select 1 from ods.t")));
    }

    // ------------------------------------------------------------------

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 2000; c = c.getCause()) {
            sb.append(c).append(' ');
        }
        return sb.toString();
    }

    private static Throwable runQuietly(Runnable task) {
        try {
            task.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /** 引用一下，避免 IDE 把这个类判成未使用。 */
    @SuppressWarnings("unused")
    private static final Class<?> GUARDED = ParseCancellation.class;
}
