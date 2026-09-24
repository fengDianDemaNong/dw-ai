package controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.dwai.lineage.Main;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 六个页面在「不同租户」与「不同项目」两个维度上的数据隔离。
 *
 * <p>这是把一次手工审计固化下来。手工审计跑完就没了 —— 下次谁新加一个
 * {@code /api/xxx/{id}} 接口忘了带租户过滤，不会有人发现，直到线上变成越权。
 *
 * <h2>验收矩阵</h2>
 * <table>
 *   <tr><th>维度</th><th>页面</th></tr>
 *   <tr><td>不同租户（A vs C）</td>
 *       <td>表基础信息 / 血缘关系 / 全局搜索 / 元数据管理 / <b>元数据服务</b></td></tr>
 *   <tr><td>同租户不同项目（A vs B）</td>
 *       <td>表基础信息 / 血缘关系 / 全局搜索 / 元数据管理</td></tr>
 * </table>
 *
 * <p><b>元数据服务只按租户隔离，不按项目</b>：它是 Gravitino / dbx 的连接配置，
 * 同一租户下所有项目共用一份，每个项目各配一遍是多余的负担。
 * 因此 A 与 B 看到的是同一批配置（{@link #metadataSourcesAreSharedWithinATenant()}），
 * 而 C 看不到 A 的任何一条。
 *
 * <h2>三个域</h2>
 * <ul>
 *   <li><b>A</b> = 默认租户 / 默认项目</li>
 *   <li><b>B</b> = <b>同租户、另一个项目</b> —— 专门证明项目级也隔离，不只是租户级。
 *       {@code TenantIsolationArchTest} 只扫 SQL 里有没有 {@code tenant_id}，
 *       扫不出漏掉 {@code project_id} 的情况，这个维度只能靠这里的用例守住</li>
 *   <li><b>C</b> = 另一个租户</li>
 * </ul>
 *
 * <h2>两条判据上的坑（手工审计时都踩过）</h2>
 * <ol>
 *   <li><b>不能用「返回是否为空」当判据。</b> 三个域灌的是同一套结构，
 *       它们本来就各有自己的 {@code ods_user}，「非空」不等于「泄漏」。
 *       必须比对 <b>id 集合</b> —— 同一张物理表里的行归属唯一，id 不会说谎</li>
 *   <li><b>验证 versionId 隔离前，必须先证明 versionId 确实生效。</b>
 *       否则该参数根本没被实现时，「传别人的 id 没效果」这条断言同样会绿</li>
 * </ol>
 */
@SpringBootTest(classes = Main.class, properties = {
        "database.type=h2",
        "database.url=jdbc:h2:mem:tenant_isolation_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "database.username=sa",
        "database.password=",
        // 与 TenantAdminApiTest 同理，必须是 standalone：本类要靠「建多个租户 +
        // 用租户头切换上下文」来验隔离，而租户写操作与租户头在 standard / multi 下
        // 都被设计性地关掉了（见 TenantAdminApiTest 上的说明）。
        // 令牌不用带：standalone 的 /api/** 全放行。
        "lineage.run-mode=standalone"
})
@AutoConfigureMockMvc
class TenantIsolationApiTest {


    /**
     * 建表必须发生在 Spring 容器启动<b>之前</b>。
     *
     * <p>先建表再起 Spring 上下文；Flyway 见到非空库只会 baseline，不会重复建表。
     * 静态初始化块在类加载时执行，早于 Spring 上下文创建，正好赶在校验之前。
     * 放进 {@code @BeforeAll} 就晚了 —— 那时上下文已经在创建。
     */
    static {
        persistence.TestSchema.apply(
                persistence.TestDataSources.h2("tenant_isolation_test"), "h2");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    /** 一个隔离域：租户 + 项目。 */
    private record Domain(String label, long tenantId, long projectId) {
    }

    private static Domain a;
    private static Domain b;
    private static Domain c;

    @BeforeEach
    void setUpDomains() throws Exception {
        if (a != null) {
            return;
        }
        a = new Domain("A(默认租户/默认项目)", 1, 1);

        // B：同一个租户下的第二个项目
        long bProject = MAPPER.readTree(body(mvc.perform(post("/api/tenants/1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"iso-p2\",\"name\":\"隔离验证项目二\"}"))))
                .get("id").asLong();
        b = new Domain("B(同租户/另一个项目)", 1, bProject);

        // C：另一个租户（连带自动生成默认项目）
        JsonNode tenantC = MAPPER.readTree(body(mvc.perform(post("/api/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"iso-t2\",\"name\":\"隔离验证租户二\"}"))));
        c = new Domain("C(另一个租户)", tenantC.get("id").asLong(),
                tenantC.get("projects").get(0).get("id").asLong());

        for (Domain d : List.of(a, b, c)) {
            seed(d);
        }
    }

    /** 每个域灌一套结构相同、但归属不同的数据。结构相同正是为了逼出「靠 id 而不是靠内容」的判据。 */
    private void seed(Domain d) throws Exception {
        ddl(d, "create table iso_ods.orders ("
                + "order_id bigint comment '订单ID', "
                + "amount decimal(12,2) comment '订单金额', "
                + "city string comment '城市') comment '订单表'");
        ddl(d, "create table iso_dws.order_sum ("
                + "city string comment '城市', "
                + "total decimal(16,2) comment '总金额') comment '订单汇总'");
        // 两个版本，且边数不同 —— 供 versionId 用例做区分
        saveLineage(d, "insert into iso_dws.order_sum "
                + "select city, amount from iso_ods.orders");
        saveLineage(d, "insert into iso_dws.order_sum "
                + "select city from iso_ods.orders");
    }

    // ------------------------------------------------------------------
    // 1. id 集合互不重叠 —— 最不容易被骗的判据
    // ------------------------------------------------------------------

    @Test
    void resourceIdsNeverOverlapAcrossDomains() throws Exception {
        Resources ra = collect(a);
        Resources rb = collect(b);
        Resources rc = collect(c);

        assertFalse(ra.isEmpty(), "域 A 没采到任何资源，说明用例本身失效了");
        assertFalse(rb.isEmpty(), "域 B 没采到任何资源，说明用例本身失效了");

        for (String kind : Resources.KINDS) {
            if (kind.equals("source")) {
                // 元数据服务是租户级共享的：A 与 B 同租户，看到的必须是同一批，
                // 所以这里不能要求它们不重叠 —— 单独在下面断言
                assertDisjoint(kind, a, ra.get(kind), c, rc.get(kind));
                assertDisjoint(kind, b, rb.get(kind), c, rc.get(kind));
                continue;
            }
            assertDisjoint(kind, a, ra.get(kind), b, rb.get(kind));
            assertDisjoint(kind, a, ra.get(kind), c, rc.get(kind));
            assertDisjoint(kind, b, rb.get(kind), c, rc.get(kind));
        }
    }

    /**
     * 元数据服务在<b>租户内共享</b>：同一租户的两个项目看到同一批配置。
     *
     * <p>与其它资源相反 —— 那些都是项目级的。这里刻意做成正向断言，
     * 否则「共享」这件事只会体现为上面那个 continue，读代码的人看不出是有意为之。
     */
    @Test
    void metadataSourcesAreSharedWithinATenant() throws Exception {
        assertEquals(collect(a).get("source"), collect(b).get("source"),
                "同一租户下的两个项目应当看到同一批元数据服务");
        assertFalse(collect(a).get("source").isEmpty(), "没采到配置，用例失效");
    }

    // ------------------------------------------------------------------
    // 2. 按 id 直取别的域的资源 —— 列表过滤对了、by-id 漏了，是最典型的越权形态
    // ------------------------------------------------------------------

    @Test
    void readingAnotherDomainsResourceByIdIsRejected() throws Exception {
        Resources ra = collect(a);
        long metaTable = first(ra.get("meta_table"));
        long metaColumn = first(ra.get("meta_column"));
        long catTable = first(ra.get("cat_table"));
        long catColumn = first(ra.get("cat_column"));

        List<String> paths = List.of(
                "/api/meta/tables/" + metaTable,
                "/api/meta/tables/" + metaTable + "/columns",
                "/api/catalog/tables/" + catTable,
                "/api/catalog/tables/" + catTable + "/columns",
                "/api/catalog/tables/" + catTable + "/upstream",
                "/api/catalog/tables/" + catTable + "/referenced",
                "/api/catalog/tables/" + catTable + "/versions",
                "/api/catalog/columns/" + catColumn + "/upstream",
                "/api/catalog/columns/" + catColumn + "/downstream");

        for (Domain intruder : List.of(b, c)) {
            for (String path : paths) {
                assertRejected(intruder, get(path), "读 " + path);
            }
            assertRejected(intruder, put("/api/meta/tables/" + metaTable)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"comment\":\"越权改的\"}"), "改元数据表");
            assertRejected(intruder, put("/api/meta/columns/" + metaColumn)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"comment\":\"越权改的\"}"), "改元数据字段");
            assertRejected(intruder, delete("/api/meta/tables/" + metaTable), "删元数据表");
        }

        // 越权操作全被拒之后，A 的数据必须原封不动
        assertEquals(ra.get("meta_table"), collect(a).get("meta_table"),
                "A 的元数据表在被越权访问后发生了变化");
    }

    @Test
    void modifyingAnotherTenantsMetadataSourceIsRejected() throws Exception {
        long sourceId = first(collect(a).get("source"));
        // 只有跨租户才算越权。同租户的另一个项目本来就该能改 —— 配置是共享的
        assertRejected(c, put("/api/metadata-sources/" + sourceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"越权改的\",\"type\":\"CATALOG\","
                        + "\"baseUrl\":\"local://catalog\",\"priority\":50,\"enabled\":true}"),
                "改元数据服务");
        assertRejected(c, delete("/api/metadata-sources/" + sourceId), "删元数据服务");
    }

    // ------------------------------------------------------------------
    // 3~4. 列表与搜索只返回本域的行
    // ------------------------------------------------------------------

    @Test
    void listAndSearchReturnOnlyOwnRows() throws Exception {
        Resources ra = collect(a);
        Resources rb = collect(b);
        Resources rc = collect(c);

        record Case(Domain domain, Resources own, Set<Long> others) {
        }
        List<Case> cases = List.of(
                new Case(a, ra, union(rb.get("cat_table"), rc.get("cat_table"))),
                new Case(b, rb, union(ra.get("cat_table"), rc.get("cat_table"))),
                new Case(c, rc, union(ra.get("cat_table"), rb.get("cat_table"))));

        for (Case cs : cases) {
            // 全局搜索：三个域搜同一个关键字，各自只能拿到自己的表
            Set<Long> hits = new TreeSet<>();
            for (JsonNode hit : MAPPER.readTree(body(
                    mvc.perform(withDomain(get("/api/catalog/search?keyword=orders"), cs.domain))))) {
                hits.add(hit.get("tableId").asLong());
            }
            assertFalse(hits.isEmpty(), cs.domain.label() + " 搜不到自己的表，用例失效");
            assertTrue(cs.own().get("cat_table").containsAll(hits),
                    cs.domain.label() + " 全局搜索返回了不属于自己的表: " + hits);
            assertDisjointSets(cs.domain.label() + " 全局搜索", hits, cs.others());

            // 元数据列表
            Set<Long> metaIds = new TreeSet<>();
            for (JsonNode t : MAPPER.readTree(body(withDomainGet(
                    "/api/meta/tables?size=200", cs.domain))).get("items")) {
                metaIds.add(t.get("id").asLong());
            }
            assertEquals(cs.own().get("meta_table"), metaIds,
                    cs.domain.label() + " 元数据列表与自己拥有的 id 不一致");
        }
    }

    // ------------------------------------------------------------------
    // 5. 血缘分析页：跨域 sourceId
    // ------------------------------------------------------------------

    @Test
    void usingAnotherTenantsMetadataSourceIsRejected() throws Exception {
        long sourceOfA = first(collect(a).get("source"));
        long sourceOfC = first(collect(c).get("source"));
        assertNotEquals(sourceOfA, sourceOfC, "两个租户的内置来源 id 不该相同");

        String sql = "insert into iso_dws.probe select city from iso_ods.orders";
        String payload = "{\"dbType\":\"hive\",\"querySql\":\"" + sql + "\",\"sourceId\":";

        assertRejected(c, post("/api/lineage/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload + sourceOfA + "}"), "用 A 的 sourceId 解析");
        assertRejected(c, get("/api/mate/matelakes?sourceId=" + sourceOfA),
                "用 A 的 sourceId 浏览 Gravitino");

        // 用自己租户的来源必须正常 —— 否则「全都拒绝」也能让上面两条通过
        mvc.perform(withDomain(post("/api/lineage/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload + sourceOfC + "}"), c))
                .andExpect(status().isOk());

        // 同租户的另一个项目用 A 的来源必须正常 —— 这是「共享」的实际表现
        mvc.perform(withDomain(post("/api/lineage/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload + sourceOfA + "}"), b))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // 6. 血缘关系页：versionId
    // ------------------------------------------------------------------

    @Test
    void versionIdIsFunctionalAndScopedToItsDomain() throws Exception {
        long tableId = targetTableId(b);
        JsonNode versions = MAPPER.readTree(body(withDomainGet(
                "/api/catalog/tables/" + tableId + "/versions", b)));
        assertTrue(versions.size() >= 2, "域 B 应当有至少两个版本，实际 " + versions.size());

        String fullName = MAPPER.readTree(body(withDomainGet(
                "/api/catalog/tables/" + tableId, b))).get("fullName").asText();

        // 先证明 versionId 真的有用：两个版本给出不同的边数。
        // 少了这一步，下面那条断言在 versionId 根本没实现时也会绿。
        long v1 = versions.get(0).get("id").asLong();
        long v2 = versions.get(1).get("id").asLong();
        int edges1 = edgeCount(b, fullName, v1);
        int edges2 = edgeCount(b, fullName, v2);
        assertNotEquals(edges1, edges2,
                "两个版本的边数相同，这个用例区分不出 versionId 是否生效（需要造边数不同的版本）");

        // 再验证跨域：拿 A 的 versionId 查 B，应当退回 B 自己的当前版本
        long versionOfA = first(collect(a).get("version"));
        assertFalse(Set.of(v1, v2).contains(versionOfA), "取到的 A 版本 id 与 B 重叠了");
        assertEquals(edgeCount(b, fullName, null), edgeCount(b, fullName, versionOfA),
                "传入 A 的 versionId 后结果变了，说明跨域版本被采纳");
    }

    // ------------------------------------------------------------------
    // 7. 写入只落在调用方的域
    // ------------------------------------------------------------------

    @Test
    void savingLineageOnlyAffectsCallersDomain() throws Exception {
        Set<Long> beforeA = collect(a).get("cat_table");
        Set<Long> beforeB = collect(b).get("cat_table");
        Set<Long> beforeC = collect(c).get("cat_table");

        saveLineage(c, "insert into iso_probe.tgt select id from iso_probe.src");

        assertEquals(beforeA, collect(a).get("cat_table"), "在 C 写入却影响了 A");
        assertEquals(beforeB, collect(b).get("cat_table"), "在 C 写入却影响了 B");
        assertTrue(collect(c).get("cat_table").size() > beforeC.size(), "C 自己没有新增");
    }

    // ------------------------------------------------------------------
    // 采集与断言
    // ------------------------------------------------------------------

    /** 一个域拥有的全部资源 id，按类型分组。 */
    private record Resources(java.util.Map<String, Set<Long>> byKind) {
        static final List<String> KINDS = List.of(
                "meta_table", "meta_column", "cat_table", "cat_column", "version", "source");

        Set<Long> get(String kind) {
            return byKind.getOrDefault(kind, Set.of());
        }

        boolean isEmpty() {
            return byKind.values().stream().allMatch(Set::isEmpty);
        }
    }

    private Resources collect(Domain d) throws Exception {
        java.util.Map<String, Set<Long>> out = new java.util.LinkedHashMap<>();
        Resources.KINDS.forEach(k -> out.put(k, new TreeSet<>()));

        for (JsonNode t : MAPPER.readTree(body(withDomainGet("/api/meta/tables?size=200", d)))
                .get("items")) {
            long id = t.get("id").asLong();
            out.get("meta_table").add(id);
            for (JsonNode col : MAPPER.readTree(body(withDomainGet(
                    "/api/meta/tables/" + id + "/columns", d)))) {
                out.get("meta_column").add(col.get("id").asLong());
            }
        }
        for (JsonNode t : MAPPER.readTree(body(withDomainGet("/api/catalog/tables", d)))) {
            long id = t.get("id").asLong();
            out.get("cat_table").add(id);
            for (JsonNode col : MAPPER.readTree(body(withDomainGet(
                    "/api/catalog/tables/" + id + "/columns", d)))) {
                out.get("cat_column").add(col.get("id").asLong());
            }
            for (JsonNode v : MAPPER.readTree(body(withDomainGet(
                    "/api/catalog/tables/" + id + "/versions", d)))) {
                out.get("version").add(v.get("id").asLong());
            }
        }
        for (JsonNode s : MAPPER.readTree(body(withDomainGet("/api/metadata-sources", d)))) {
            out.get("source").add(s.get("id").asLong());
        }
        return new Resources(out);
    }

    private static void assertDisjoint(String kind, Domain d1, Set<Long> s1,
                                       Domain d2, Set<Long> s2) {
        Set<Long> overlap = new TreeSet<>(s1);
        overlap.retainAll(s2);
        assertTrue(overlap.isEmpty(),
                kind + " 的 id 在 " + d1.label() + " 与 " + d2.label() + " 之间重叠: " + overlap);
    }

    private static void assertDisjointSets(String what, Set<Long> got, Set<Long> others) {
        Set<Long> overlap = new TreeSet<>(got);
        overlap.retainAll(others);
        assertTrue(overlap.isEmpty(), what + " 返回了其它域的行: " + overlap);
    }

    /** 断言该请求在这个域下被拒绝（4xx/5xx 都算拒绝，但要求不是 2xx）。 */
    private void assertRejected(Domain d, RequestBuilder rb, String what) throws Exception {
        int code = mvc.perform(withDomain(rb, d)).andReturn().getResponse().getStatus();
        assertTrue(code >= 400,
                d.label() + " 竟然可以" + what + "（HTTP " + code + "），存在越权");
    }

    // ------------------------------------------------------------------
    // HTTP 小工具
    // ------------------------------------------------------------------

    private static RequestBuilder withDomain(Object builder, Domain d) {
        var b = (org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) builder;
        return b.header("X-Tenant-Id", d.tenantId()).header("X-Project-Id", d.projectId());
    }

    private ResultActions withDomainGet(String path, Domain d) throws Exception {
        return mvc.perform(withDomain(get(path), d));
    }

    private void ddl(Domain d, String statement) throws Exception {
        mvc.perform(withDomain(post("/api/meta/ddl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(java.util.Map.of(
                                "dbType", "hive", "ddl", statement))), d))
                .andExpect(status().isOk());
    }

    private void saveLineage(Domain d, String sql) throws Exception {
        mvc.perform(withDomain(post("/api/lineage/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(java.util.Map.of(
                                "dbType", "hive", "querySql", sql))), d))
                .andExpect(status().isOk());
    }

    private long targetTableId(Domain d) throws Exception {
        for (JsonNode t : MAPPER.readTree(body(withDomainGet("/api/catalog/tables", d)))) {
            if ("order_sum".equals(t.get("tableName").asText())) {
                return t.get("id").asLong();
            }
        }
        throw new IllegalStateException("域 " + d.label() + " 里没找到 order_sum");
    }

    private int edgeCount(Domain d, String start, Long versionId) throws Exception {
        String path = "/api/lineage/graph?start=" + start + "&direction=up&depth=3"
                + (versionId == null ? "" : "&versionId=" + versionId);
        JsonNode graph = MAPPER.readTree(body(withDomainGet(path, d)));
        JsonNode section = graph.path("data").path("withProcessData").path("data");
        int edges = 0;
        for (JsonNode node : section) {
            edges += node.path("refFields").size();
        }
        return edges;
    }

    private static Set<Long> union(Set<Long> s1, Set<Long> s2) {
        Set<Long> out = new LinkedHashSet<>(s1);
        out.addAll(s2);
        return out;
    }

    private static long first(Set<Long> ids) {
        assertFalse(ids.isEmpty(), "没有可用的资源 id，用例失效");
        return ids.iterator().next();
    }

    /** MockMvc 默认按 ISO-8859-1 解码，中文会变乱码，必须显式指定 UTF-8。 */
    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static org.springframework.test.web.servlet.result.StatusResultMatchers status() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status();
    }
}
