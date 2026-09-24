package metadata;

import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.sqlflow.metadata.SchemaTable;
import io.github.melin.sqlflow.metadata.SimpleMetadataService;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * provider 产出的 {@link SchemaTable} 必须能被分析器按名字找回来。
 *
 * <h2>这条用例在防什么</h2>
 *
 * 整条链路上有<b>两个不同的</b> {@code QualifiedObjectName}，很容易搞混：
 *
 * <ol>
 *   <li><b>喂给 provider 的</b>：{@code LineageAnalysisPipeline.collectReferencedTables()} 造的，
 *       它把 null catalog 规范成了<b>空串</b>；</li>
 *   <li><b>分析器查表用的</b>：{@code MetadataUtil.createQualifiedObjectName()} 造的，
 *       两段式表名（{@code ods.user_log}）时 catalog 是 <b>null</b>。</li>
 * </ol>
 *
 * 而 {@code SimpleMetadataService} 是按 {@code toString()} 做<b>字符串匹配</b>找表的，
 * 两个 {@code toString()} 判的都是 {@code catalogName != null}（不是空判断）。
 *
 * <p>于是 provider 若把第 1 个对象的 catalog（空串）原样塞进 SchemaTable，
 * 就会拼出 {@code ".ods.user_log"}，而分析器找的是 {@code "ods.user_log"} —— 永远匹配不上。
 *
 * <p>症状极具迷惑性：provider 明明把表放进了 {@code resolved}、{@code unresolvedTables} 也是空的，
 * 解析却报 {@code table xxx metadata not exists}。Gravitino / dbx / 本地目录三个 provider
 * 都踩过（{@code DdlMetadataProvider} 因为传的是解析器原始的 null catalog 而幸免），
 * 且因为外部服务不易联调而长期没被发现。
 *
 * <p>所以这里断言的是「<b>按分析器的方式能不能查到</b>」，而不是「resolved 里有没有」——
 * 后者对这个 bug 完全免疫，正是它躲过既有测试的原因。
 */
public class ProviderSchemaTableLookupTest {

    /** 分析器真正用来查表的 key：两段式表名，catalog 为 null。 */
    private static final QualifiedObjectName ANALYZER_LOOKUP =
            new QualifiedObjectName(null, "ods", "user_log");

    /** 喂给 provider 的那个：catalog 被规范成了空串。 */
    private static final QualifiedObjectName PROVIDER_INPUT =
            new QualifiedObjectName("", "ods", "user_log");

    @Test
    public void tableWithNormalizedNullCatalogIsFoundByAnalyzer() {
        SimpleMetadataService service = new SimpleMetadataService("default");
        // 四参构造（带 catalog）+ 不设分区列：sqlflow 1.0.7 没有「带 catalog 又带分区列」
        // 的构造器，而这几条用例验的是 catalog 归一化，跟分区列无关
        service.addTableMetadata(new SchemaTable(null, "ods", "user_log",
                List.of("id", "uname")));

        Optional<SchemaTable> found = service.getTableSchema(ANALYZER_LOOKUP);

        assertTrue("catalog 归一成 null 后应能查到", found.isPresent());
        assertEquals(List.of("id", "uname"), found.get().getColumns());
    }

    /**
     * 反向验证坏行为确实存在：直接把空串 catalog 塞进去就查不到。
     *
     * <p>这条同时是个哨兵 —— 若哪天 sqlflow 改成按空判断，它会失败，
     * 提醒我们 {@code SchemaTables} 的归一化可以去掉了。
     */
    @Test
    public void tableWithBlankCatalogIsNotFound_whichIsWhyWeNormalize() {
        SimpleMetadataService service = new SimpleMetadataService("default");
        service.addTableMetadata(new SchemaTable(PROVIDER_INPUT.getCatalogName(),
                "ods", "user_log", List.of("id")));

        assertTrue("空串 catalog 会拼出 .ods.user_log，分析器找不到",
                service.getTableSchema(ANALYZER_LOOKUP).isEmpty());
    }

    /** 真实 catalog 名要保留，不能被一并抹成 null。 */
    @Test
    public void realCatalogIsPreserved() {
        QualifiedObjectName threePartName =
                new QualifiedObjectName("hive_prod", "ods", "user_log");
        SimpleMetadataService service = new SimpleMetadataService("default");
        service.addTableMetadata(new SchemaTable("hive_prod", "ods", "user_log",
                List.of("id")));

        assertTrue(service.getTableSchema(threePartName).isPresent());
    }
}
