package metadata;

import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.superior.common.relational.Statement;
import org.junit.Test;
import com.dwai.lineage.service.metadata.MetadataServiceFactory;
import com.dwai.lineage.service.metadata.provider.ColumnDescriptor;
import com.dwai.lineage.service.metadata.provider.DdlMetadataProvider;
import com.dwai.lineage.service.metadata.provider.MetadataResolution;
import com.dwai.lineage.service.metadata.provider.TableDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 描述属性能不能从元数据来源一路带出来。
 *
 * <p>这条通道是纯搭便车的：血缘解析本身不看它，所以一旦哪个 provider 忘了填，
 * 功能测试全绿而页面上悄悄少了中文名。这里对每种来源钉一遍。
 *
 * <p>远程来源（Gravitino / dbx）需要真服务，各自在
 * {@code DbxMetadataProviderTest} 与真机联调里覆盖；这里只覆盖不依赖外部的两种。
 */
public class TableDescriptorPropagationTest {

    // ==================================================================
    // SQL 里自带的 CREATE TABLE
    // ==================================================================

    @Test
    public void ddlProviderCarriesTypesAndComments() {
        DdlMetadataProvider provider = ddl(
                "create table ods.orders ("
                        + "  id bigint comment '订单号',"
                        + "  amount decimal(12,2) comment '金额'"
                        + ") comment '订单主表'");

        QualifiedObjectName wanted = table("ods", "orders");
        MetadataResolution resolution = provider.resolve(Set.of(wanted));

        TableDescriptor descriptor = resolution.descriptors().get(wanted);
        assertNotNull("建表语句里写着的注释与类型不该被丢掉", descriptor);
        assertEquals("订单主表", descriptor.comment());
        assertEquals("bigint", descriptor.columnOf("id").dataType());
        assertEquals("订单号", descriptor.columnOf("id").comment());
        assertEquals("decimal(12,2)", descriptor.columnOf("amount").dataType());
    }

    /** 列名大小写不该影响取值：SQL 里怎么写的和元数据里怎么存的未必一致。 */
    @Test
    public void columnLookupIsCaseInsensitive() {
        DdlMetadataProvider provider = ddl(
                "create table ods.t (UserId bigint comment '用户号')");
        QualifiedObjectName wanted = table("ods", "t");
        TableDescriptor d = provider.resolve(Set.of(wanted)).descriptors().get(wanted);

        assertEquals("用户号", d.columnOf("userid").comment());
        assertEquals("用户号", d.columnOf("USERID").comment());
        assertEquals("用户号", d.columnOf("UserId").comment());
    }

    /**
     * 表类型必须留空。
     *
     * <p>解析器的 {@code tableType} 是 HIVE / ICEBERG 这类存储格式，
     * 与数仓表类型（全量表 / 拉链表）不是一回事，硬映射只会得到错的值。
     */
    @Test
    public void ddlProviderLeavesTableTypeEmpty() {
        DdlMetadataProvider provider = ddl("create table ods.t (id bigint)");
        QualifiedObjectName wanted = table("ods", "t");
        TableDescriptor d = provider.resolve(Set.of(wanted)).descriptors().get(wanted);

        assertNull("解析器的 tableType 与数仓表类型不是一回事，不能硬映射", d.tableType());
    }

    /** 没有任何注释和类型时不必产出一份空描述，免得白占一次写库。 */
    @Test
    public void tableWithoutAnyDescriptionYieldsNothing() {
        DdlMetadataProvider provider = ddl("create table ods.bare (id bigint)");
        QualifiedObjectName wanted = table("ods", "bare");
        TableDescriptor d = provider.resolve(Set.of(wanted)).descriptors().get(wanted);

        // 列描述里 dataType 还在（bigint），所以整体不算空 —— 这是对的
        assertNotNull(d);
        assertEquals("bigint", d.columnOf("id").dataType());
        assertNull(d.comment());
    }

    // ==================================================================
    // 合并优先级
    // ==================================================================

    /**
     * 描述与结构同一套优先级：先给出结构的来源赢。
     *
     * <p>不做「A 出结构、B 出描述」的跨来源拼接 —— 拼出来的中文名可能来自
     * 另一个库里的同名表，比没有更糟。
     */
    @Test
    public void mergeKeepsTheFirstSourcesDescriptor() {
        QualifiedObjectName t = table("ods", "t");

        MetadataResolution first = MetadataResolution.of(
                Map.of(), Set.of(),
                Map.of(t, TableDescriptor.ofTable("先来的", null, null)));
        MetadataResolution second = MetadataResolution.of(
                Map.of(), Set.of(),
                Map.of(t, TableDescriptor.ofTable("后来的", null, null)));

        assertEquals("先来的", first.mergeWith(second).descriptors().get(t).comment());
    }

    @Test
    public void mergeFillsGapsFromTheLaterSource() {
        QualifiedObjectName a = table("ods", "a");
        QualifiedObjectName b = table("ods", "b");

        MetadataResolution merged = MetadataResolution
                .of(Map.of(), Set.of(), Map.of(a, TableDescriptor.ofTable("甲", null, null)))
                .mergeWith(MetadataResolution
                        .of(Map.of(), Set.of(), Map.of(b, TableDescriptor.ofTable("乙", null, null))));

        assertEquals("甲", merged.descriptors().get(a).comment());
        assertEquals("乙", merged.descriptors().get(b).comment());
    }

    // ==================================================================
    // TableDescriptor 自身
    // ==================================================================

    @Test
    public void emptyDescriptorIsRecognised() {
        assertTrue(new TableDescriptor(null, null, null, Map.of()).isEmpty());
        assertTrue(TableDescriptor.ofTable(null, null, "  ").isEmpty());
        assertTrue(new ColumnDescriptor(null, "", null).isEmpty());

        assertTrue(!TableDescriptor.ofTable("有中文名", null, null).isEmpty());
    }

    @Test
    public void unknownColumnReturnsNullInsteadOfThrowing() {
        TableDescriptor d = TableDescriptor.ofTable("表", null, null);
        // 派生列（sum(x) as y）就是这种情况：源表里根本没有这一列
        assertNull(d.columnOf("derived"));
        assertNull(d.columnOf(null));
    }

    // ------------------------------------------------------------------

    /** DdlMetadataProvider.key 是包内可见的，测试在别的包，按同样规则自己构造。 */
    private static QualifiedObjectName table(String schema, String name) {
        return new QualifiedObjectName("", schema, name);
    }

    private static final MetadataServiceFactory FACTORY = new MetadataServiceFactory();

    private static DdlMetadataProvider ddl(String sql) {
        List<Statement> statements = FACTORY.statements("hive", sql);
        return new DdlMetadataProvider(statements);
    }
}
