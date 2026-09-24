package metadata;

import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.sqlflow.metadata.SchemaTable;
import org.junit.Test;
import com.dwai.lineage.service.metadata.LineageAnalysisPipeline;
import com.dwai.lineage.service.metadata.provider.CompositeMetadataProvider;
import com.dwai.lineage.service.metadata.provider.MetadataProvider;
import com.dwai.lineage.service.metadata.provider.MetadataResolution;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * MetadataProvider SPI 的优先级串联与降级行为。
 */
public class MetadataProviderTest {

    private static QualifiedObjectName table(String schema, String name) {
        return new QualifiedObjectName("", schema, name);
    }

    /** 一个只认识固定几张表的假 provider。 */
    private static final class StubProvider implements MetadataProvider {
        private final String name;
        private final Map<QualifiedObjectName, SchemaTable> known = new LinkedHashMap<>();
        final AtomicInteger callCount = new AtomicInteger();
        Set<QualifiedObjectName> lastAsked;

        StubProvider(String name) {
            this.name = name;
        }

        StubProvider with(String schema, String tableName, String... columns) {
            known.put(table(schema, tableName),
                    new SchemaTable(schema, tableName, List.of(columns)));
            return this;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public MetadataResolution resolve(Set<QualifiedObjectName> tables) {
            callCount.incrementAndGet();
            lastAsked = new LinkedHashSet<>(tables);
            Map<QualifiedObjectName, SchemaTable> resolved = new LinkedHashMap<>();
            Set<QualifiedObjectName> unresolved = new LinkedHashSet<>();
            for (QualifiedObjectName t : tables) {
                SchemaTable found = known.get(t);
                if (found != null) {
                    resolved.put(t, found);
                } else {
                    unresolved.add(t);
                }
            }
            return MetadataResolution.of(resolved, unresolved);
        }
    }

    /** 会整体抛异常的 provider，模拟服务宕机。 */
    private static final class BrokenProvider implements MetadataProvider {
        @Override
        public String name() {
            return "broken";
        }

        @Override
        public MetadataResolution resolve(Set<QualifiedObjectName> tables) {
            throw new IllegalStateException("服务不可达");
        }
    }

    @Test
    public void higherPriorityProviderWins() {
        StubProvider high = new StubProvider("high").with("db", "t", "a", "b");
        StubProvider low = new StubProvider("low").with("db", "t", "x", "y", "z");

        MetadataResolution r = new CompositeMetadataProvider(List.of(high, low))
                .resolve(Set.of(table("db", "t")));

        assertEquals("应采用高优先级来源的结构",
                List.of("a", "b"), r.resolved().get(table("db", "t")).getColumns());
    }

    /** 前一个 provider 已解析的表，不应再问后一个。 */
    @Test
    public void resolvedTablesAreNotAskedAgain() {
        StubProvider high = new StubProvider("high").with("db", "t1", "a");
        StubProvider low = new StubProvider("low").with("db", "t2", "b");

        MetadataResolution r = new CompositeMetadataProvider(List.of(high, low))
                .resolve(new LinkedHashSet<>(List.of(table("db", "t1"), table("db", "t2"))));

        assertEquals(2, r.resolved().size());
        assertEquals("低优先级只应被问及未解析的表",
                Set.of(table("db", "t2")), low.lastAsked);
    }

    /** 某个来源整体不可用时应降级到下一个，而不是让解析失败。 */
    @Test
    public void brokenProviderDegradesToNext() {
        StubProvider fallback = new StubProvider("fallback").with("db", "t", "a");

        MetadataResolution r = new CompositeMetadataProvider(List.of(new BrokenProvider(), fallback))
                .resolve(Set.of(table("db", "t")));

        assertEquals(1, r.resolved().size());
        assertTrue("应记录来源不可用的告警",
                r.warnings().stream().anyMatch(w -> w.contains("broken")));
    }

    /** 所有来源都拿不到的表，必须出现在 unresolved 中。 */
    @Test
    public void unknownTablesAreReportedAsUnresolved() {
        StubProvider only = new StubProvider("only").with("db", "known", "a");

        MetadataResolution r = new CompositeMetadataProvider(List.of(only))
                .resolve(new LinkedHashSet<>(List.of(table("db", "known"), table("db", "missing"))));

        assertEquals(Set.of(table("db", "known")), r.resolved().keySet());
        assertEquals(Set.of(table("db", "missing")), r.unresolved());
    }

    /** 全部来源都失败时不抛异常，只是结果为空。 */
    @Test
    public void allProvidersBrokenYieldsEmptyResultNotException() {
        MetadataResolution r = new CompositeMetadataProvider(List.of(new BrokenProvider(), new BrokenProvider()))
                .resolve(Set.of(table("db", "t")));

        assertTrue(r.resolved().isEmpty());
        assertEquals(Set.of(table("db", "t")), r.unresolved());
        assertFalse(r.warnings().isEmpty());
    }

    @Test
    public void emptyInputSkipsAllProviders() {
        StubProvider p = new StubProvider("p");

        MetadataResolution r = new CompositeMetadataProvider(List.of(p)).resolve(Set.of());

        assertTrue(r.resolved().isEmpty());
        assertEquals("空输入不应触发任何来源查询", 0, p.callCount.get());
    }

    /** chain() 会忽略 null，便于按开关拼装。 */
    @Test
    public void chainIgnoresNullProviders() {
        StubProvider p = new StubProvider("p").with("db", "t", "a");

        MetadataProvider chained = LineageAnalysisPipeline.chain(null, p, null);
        MetadataResolution r = chained.resolve(Set.of(table("db", "t")));

        assertEquals(1, r.resolved().size());
    }
}
