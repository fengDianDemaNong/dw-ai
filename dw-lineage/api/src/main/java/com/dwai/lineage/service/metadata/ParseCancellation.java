package com.dwai.lineage.service.metadata;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 给 ANTLR 解析装一个可中断的检查点。
 *
 * <h2>要解决什么</h2>
 * {@code SqlParseExecutor} 给解析设了超时，超时后调 {@code Future.cancel(true)} ——
 * 但那只是给线程打了个中断标志，<b>ANTLR 从不检查中断</b>，于是线程继续跑。
 * 调用方拿到了「解析超时」的响应，服务器这边却还有一个线程在满负荷空转，
 * 占着 16MB 栈直到它自己跑完。并发上限是 16，反复超时就会被这些线程占满，
 * 后续请求全被拒。
 *
 * <p>ANTLR 没有内建的取消机制（{@code Parser} 上没有任何 cancel / timeout 方法），
 * 但它有 {@code addParseListener}：解析每退出一条语法规则都会回调。
 * 那就是我们唯一能插进去的检查点。
 *
 * <h2>覆盖范围（重要）</h2>
 * <b>只覆盖 sqlflow 那一段解析</b>。前面还有一段 superior-sql-parser 负责按方言拆分语句
 * （{@code SparkSqlHelper.parseMultiStatement} 之类的静态方法），
 * 那里没有任何可注入的钩子，卡在那一段仍然打断不了。
 * 也就是说这不是根治，是把「一定停不下来」变成「大部分情况停得下来」。
 */
public final class ParseCancellation {

    private static final Logger logger = LoggerFactory.getLogger(ParseCancellation.class);

    /**
     * 每多少条规则查一次中断标志。
     *
     * <p>不是每次都查：一条复杂 SQL 的规则退出次数在百万级，而
     * {@code Thread.isInterrupted()} 要读一次线程状态。256 次一查，
     * 既不至于让检查本身成为开销，也能在毫秒级内响应取消。
     */
    private static final int CHECK_EVERY = 256;

    private ParseCancellation() {
    }

    /**
     * 装到 ANTLR 的 {@code Parser} 上。
     *
     * <p>{@code addParseListener} 要求解析器在构建语法树（{@code buildParseTree}）；
     * 万一哪个版本关掉了它，这里会抛 {@code UnsupportedOperationException}。
     * 那种情况下<b>不要让解析失败</b> —— 装不上检查点只是失去一个优化，
     * 而解析本身是核心功能。记一条日志然后继续。
     */
    public static void install(Parser parser) {
        try {
            parser.addParseListener(new InterruptCheck());
        } catch (UnsupportedOperationException e) {
            logger.debug("解析器未构建语法树，取消检查点未装上；超时后线程将无法提前结束");
        }
    }

    /** 每退出 {@link #CHECK_EVERY} 条规则查一次中断。 */
    private static final class InterruptCheck implements ParseTreeListener {

        private int rules;

        @Override
        public void exitEveryRule(ParserRuleContext ctx) {
            if (++rules % CHECK_EVERY == 0 && Thread.currentThread().isInterrupted()) {
                // 用 ParseCancellationException：它是 ANTLR 自己的类型，
                // 沿解析栈抛上去不会被 ANTLR 的错误恢复逻辑当成语法错误吞掉
                throw new ParseCancellationException("SQL 解析已取消（超时或被中断）");
            }
        }

        @Override
        public void enterEveryRule(ParserRuleContext ctx) {
            // 只在退出时查就够了：进入和退出是成对的，查一半省一半开销
        }

        @Override
        public void visitTerminal(TerminalNode node) {
        }

        @Override
        public void visitErrorNode(ErrorNode node) {
        }
    }
}
