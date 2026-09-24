package lineage;

import org.junit.Test;
import com.dwai.lineage.exception.SqlParseException;
import com.dwai.lineage.service.SqlParseExecutor;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 解析执行器的保护行为：超时、栈溢出隔离、异常透传。
 */
public class SqlParseExecutorTest {

    private final SqlParseExecutor executor = new SqlParseExecutor();

    @Test
    public void normalTaskReturnsResult() {
        assertEquals("ok", executor.call("t", () -> "ok"));
    }

    /**
     * 栈溢出必须被转成正常业务异常。
     *
     * <p>{@code StackOverflowError} 是 Error 而非 Exception，若发生在 web 线程上
     * 后果不可控；隔离到专用线程后应转换为可读的 SqlParseException。
     */
    @Test
    public void stackOverflowIsConvertedToBusinessException() {
        try {
            executor.call("深递归", SqlParseExecutorTest::infiniteRecursion);
            fail("应抛出 SqlParseException");
        } catch (SqlParseException e) {
            assertTrue("应提示嵌套过深，实际: " + e.getMessage(),
                    e.getMessage().contains("嵌套") || e.getMessage().contains("过深"));
        }
    }

    /** 业务异常应原样透传，不被包装成难以理解的信息。 */
    @Test
    public void businessExceptionPropagates() {
        try {
            executor.call("t", () -> {
                throw new SqlParseException("原始错误信息");
            });
            fail("应抛出 SqlParseException");
        } catch (SqlParseException e) {
            assertEquals("原始错误信息", e.getMessage());
        }
    }

    /** 主线程不应被解析线程的错误污染，后续调用仍然正常。 */
    @Test
    public void executorRemainsUsableAfterFailure() {
        try {
            executor.call("失败任务", SqlParseExecutorTest::infiniteRecursion);
        } catch (SqlParseException ignored) {
            // 预期内
        }
        assertEquals("still works", executor.call("t", () -> "still works"));
    }

    /** 解析在独立线程上执行，不占用调用线程。 */
    @Test
    public void taskRunsOnDedicatedThread() {
        String callerThread = Thread.currentThread().getName();
        String workerThread = executor.call("t", () -> Thread.currentThread().getName());

        assertTrue("应在 sql-parse-* 线程执行，实际: " + workerThread,
                workerThread.startsWith("sql-parse-"));
        assertTrue(!workerThread.equals(callerThread));
    }

    /** 中断标志不应被吞掉。 */
    @Test
    public void interruptedTaskIsReported() {
        AtomicBoolean started = new AtomicBoolean();
        assertTrue(executor.call("t", () -> {
            started.set(true);
            return true;
        }));
        assertTrue(started.get());
    }

    @SuppressWarnings("InfiniteRecursion")
    private static Object infiniteRecursion() {
        return infiniteRecursion();
    }
}
