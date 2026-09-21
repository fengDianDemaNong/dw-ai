package controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import com.dwai.lineage.Main;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据目录配置与临时表过滤的端到端验证。
 *
 * <p>核心是用户给的那段 SQL 走完整链路 —— 单元测试里
 * {@code TempTableFilterTest} 只验了桥接算法本身，这里验的是
 * 「配规则 → 解析 → 保存」三段接起来之后，页面上和库里各自是什么。
 * 中间任何一段没接上（比如保存路径忘了传匹配器），算法再对也没用。
 */
@SpringBootTest(classes = Main.class, properties = {
        "database.type=h2",
        "database.url=jdbc:h2:mem:data_catalog_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "database.username=sa",
        "database.password="
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataCatalogApiTest {

    /** 与其它 API 测试同理：先建表再起 Spring 上下文，Flyway 见到非空库只会 baseline，不会重复建表。 */
    static {
        persistence.TestSchema.apply(persistence.TestDataSources.h2("data_catalog_test"), "h2");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    // ==================================================================
    // 数据目录
    // ==================================================================

    /** 建库脚本种了一条默认目录，页面一进来就该看到它。 */
    @Test
    @Order(1)
    void hasExactlyOneDefaultCatalogOutOfTheBox() throws Exception {
        JsonNode list = MAPPER.readTree(body(mvc.perform(get("/api/data-catalogs"))));
        long defaults = 0;
        for (JsonNode c : list) {
            if (c.get("isDefault").asBoolean()) {
                defaults++;
            }
        }
        assertEquals(1, defaults, "默认目录必须有且只有一个：一个都没有会让导入无处可去，"
                + "多个则谁生效取决于查询顺序");
    }

    /** 设新默认时旧的必须自动取消，不能两条同时是默认。 */
    @Test
    @Order(2)
    void settingANewDefaultClearsTheOldOne() throws Exception {
        long created = MAPPER.readTree(body(mvc.perform(post("/api/data-catalogs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hive_prod\",\"description\":\"生产\"}"))
                .andExpect(status().isOk()))).get("id").asLong();

        mvc.perform(post("/api/data-catalogs/" + created + "/default")).andExpect(status().isOk());

        Set<String> defaults = new LinkedHashSet<>();
        for (JsonNode c : MAPPER.readTree(body(mvc.perform(get("/api/data-catalogs"))))) {
            if (c.get("isDefault").asBoolean()) {
                defaults.add(c.get("name").asText());
            }
        }
        assertEquals(Set.of("hive_prod"), defaults, "设了新默认，旧的必须自动取消");

        // 复位，后面的用例依赖 default 是默认目录
        long back = catalogIdByName("default");
        mvc.perform(post("/api/data-catalogs/" + back + "/default")).andExpect(status().isOk());
    }

    /** 默认目录不能删 —— 删掉就没有「导入到哪里」的答案了。 */
    @Test
    @Order(3)
    void defaultCatalogCannotBeDeleted() throws Exception {
        long id = catalogIdByName("default");
        String message = body(mvc.perform(delete("/api/data-catalogs/" + id))
                .andExpect(status().isConflict()));
        assertTrue(message.contains("默认数据目录"), "错误信息要说清为什么不能删: " + message);
    }

    /** 目录下有表时不能删，也不能改名（改名会让这些表的全名全部失效）。 */
    @Test
    @Order(4)
    void catalogWithTablesIsProtected() throws Exception {
        mvc.perform(post("/api/data-catalogs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"cat_busy\"}")).andExpect(status().isOk());
        ddlInto("cat_busy", "create table dc_ods.t1 (id bigint)");

        long id = catalogIdByName("cat_busy");
        assertTrue(body(mvc.perform(delete("/api/data-catalogs/" + id))
                .andExpect(status().isConflict())).contains("还有 1 张表"));
        assertTrue(body(mvc.perform(put("/api/data-catalogs/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"cat_renamed\"}"))
                .andExpect(status().isConflict())).contains("改名会让这些表的全名失效"));
    }

    /** 名字带点号会把 {@code catalog.schema.table} 的切分切错位，必须在入口拦掉。 */
    @Test
    @Order(5)
    void catalogNameWithDotIsRejected() throws Exception {
        mvc.perform(post("/api/data-catalogs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad.name\"}")).andExpect(status().isBadRequest());
    }

    // ==================================================================
    // 建表语句导入选目录
    // ==================================================================

    /** 不传目录名时落到默认目录，全名是三段的。 */
    @Test
    @Order(10)
    void ddlWithoutCatalogLandsInDefaultCatalog() throws Exception {
        mvc.perform(post("/api/meta/ddl").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "dbType", "hive",
                                "ddl", "create table dc_ods.no_catalog (id bigint)"))))
                .andExpect(status().isOk());

        assertEquals("default.dc_ods.no_catalog", fullNameOf("no_catalog"),
                "没指定目录要落到默认目录，而不是留一个两段的全名");
    }

    /** 建表语句自己写了三段名时以语句为准，下拉框只是没写时的兜底。 */
    @Test
    @Order(11)
    void explicitCatalogInDdlWinsOverTheDropdown() throws Exception {
        mvc.perform(post("/api/data-catalogs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"cat_explicit\"}")).andExpect(status().isOk());

        mvc.perform(post("/api/meta/ddl").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "dbType", "hive",
                                "catalogName", "default",
                                "ddl", "create table cat_explicit.dc_ods.pinned (id bigint)"))))
                .andExpect(status().isOk());

        assertEquals("cat_explicit.dc_ods.pinned", fullNameOf("pinned"),
                "一次粘进多个目录的建表语句是常见做法，下拉框不该把它们挤进同一个目录");
    }

    /** 目录不存在要在建出一批表之前就报错。 */
    @Test
    @Order(12)
    void ddlIntoUnknownCatalogFailsFast() throws Exception {
        String message = body(mvc.perform(post("/api/meta/ddl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "dbType", "hive",
                                "catalogName", "not_created_yet",
                                "ddl", "create table dc_ods.ghost (id bigint)"))))
                .andExpect(status().isBadRequest()));
        assertTrue(message.contains("数据目录不存在"), message);
        assertEquals(0, countTables("ghost"), "报错了就不该留下任何表");
    }

    // ==================================================================
    // 临时表规则
    // ==================================================================

    /** 试算框：命中时要说清是哪一条规则，不能只回一个 true。 */
    @Test
    @Order(20)
    void ruleTestSaysWhichRuleMatched() throws Exception {
        long ruleId = createRule("SCHEMA", "GLOB", "tmp");
        createRule("SCHEMA", "GLOB", "test");
        createRule("TABLE", "GLOB", "tmp_*");

        JsonNode hit = MAPPER.readTree(body(
                mvc.perform(get("/api/data-catalogs/temp-rules/test?name=tmp.bb"))));
        assertTrue(hit.get("temp").asBoolean());
        assertEquals(ruleId, hit.get("matchedRuleId").asLong(), "要指出命中的是哪一条");

        JsonNode miss = MAPPER.readTree(body(
                mvc.perform(get("/api/data-catalogs/temp-rules/test?name=ods.orders"))));
        assertFalse(miss.get("temp").asBoolean());
        assertTrue(miss.get("matchedRuleId").isNull());
    }

    /**
     * {@code tmp_*} 在通配符和正则下含义相反，试算框必须能把这个差别显出来。
     *
     * <p>正则里 {@code *} 修饰前一个字符 {@code _}，所以它匹配 {@code tmp}、{@code tmp_}，
     * <b>唯独匹配不到 {@code tmp_abc}</b> —— 而 {@code tmp_abc} 正是用户想匹配的。
     */
    @Test
    @Order(21)
    void globAndRegexDisagreeOnTmpStar() throws Exception {
        // GLOB 的 tmp_* 规则在上一个用例里已建
        assertTrue(isTemp("dc.tmp_abc"), "通配符下 tmp_* 要匹配 tmp_abc");

        long regexRule = createRule("TABLE", "REGEX", "re_tmp_*");
        assertFalse(isTemp("dc.re_tmp_abc"),
                "正则下 re_tmp_* 匹配不到 re_tmp_abc —— 这正是两种匹配方式的区别");
        assertTrue(isTemp("dc.re_tmp"), "正则下 re_tmp_* 匹配的是 re_tmp / re_tmp_ 这类");

        mvc.perform(delete("/api/data-catalogs/temp-rules/" + regexRule)).andExpect(status().isOk());
    }

    /** 正则写错要在保存时就报错：匹配器读取时是静默跳过的，这里是唯一能提示用户的地方。 */
    @Test
    @Order(22)
    void brokenRegexIsRejectedAtWriteTime() throws Exception {
        String message = body(mvc.perform(post("/api/data-catalogs/temp-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"TABLE\",\"matchType\":\"REGEX\",\"pattern\":\"tmp_[\"}"))
                .andExpect(status().isBadRequest()));
        assertTrue(message.contains("正则表达式有语法错误"), message);
    }

    // ==================================================================
    // 用户给的例子，走完整链路
    // ==================================================================

    /**
     * <pre>
     * create table tmp.bb as select * from dc_src.source_a;
     * insert into dc_sink.sink_b select * from tmp.bb;
     * </pre>
     *
     * <ul>
     *   <li>包含临时表：{@code source_a → tmp.bb → sink_b}</li>
     *   <li>不包含：{@code source_a → sink_b}，而<b>不是两段断开的图</b></li>
     *   <li>保存后查库：只有 {@code source_a → sink_b}，{@code tmp.bb} 一行都没有</li>
     * </ul>
     */
    @Test
    @Order(30)
    void userExampleBridgesThroughTempTableAndNeverPersistsIt() throws Exception {
        ddlInto("default", "create table dc_src.source_a (id bigint, amount decimal(12,2))");
        ddlInto("default", "create table dc_sink.sink_b (id bigint, amount decimal(12,2))");

        String sql = "create table tmp.bb as select id, amount from dc_src.source_a; "
                + "insert into dc_sink.sink_b select id, amount from tmp.bb";

        // 1) 勾了「包含临时表」：中间节点还在
        Set<String> withTemp = graphTables(analyze(sql, true));
        assertTrue(withTemp.contains("default.tmp.bb"), "勾了包含临时表就该看到 tmp.bb: " + withTemp);
        assertTrue(withTemp.contains("default.dc_src.source_a") && withTemp.contains("default.dc_sink.sink_b"));

        // 2) 默认（不包含）：tmp.bb 消失，但两端仍然连着
        JsonNode filtered = analyze(sql, false);
        Set<String> withoutTemp = graphTables(filtered);
        assertFalse(withoutTemp.contains("default.tmp.bb"), "不含临时表时 tmp.bb 应被穿透掉: " + withoutTemp);
        assertTrue(withoutTemp.contains("default.dc_src.source_a"),
                "source_a 必须还在 —— 掉了就说明是把节点删了而不是桥接: " + withoutTemp);
        assertTrue(withoutTemp.contains("default.dc_sink.sink_b"), withoutTemp.toString());
        assertTrue(hasEdge(filtered, "default.dc_sink.sink_b", "default.dc_src.source_a"),
                "source_a 要直接接到 sink_b，而不是断成两段互不相连的图");

        // 3) 图上少了节点必须有提示，否则用户会以为解析错了
        assertTrue(filtered.path("warnings").toString().contains("临时表"),
                "过滤掉临时表要在 warnings 里说一声: " + filtered.path("warnings"));

        // 4) 保存：库里不该有 tmp.bb
        mvc.perform(post("/api/lineage/save").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "dbType", "hive", "querySql", sql))))
                .andExpect(status().isOk());

        Set<String> stored = new LinkedHashSet<>();
        for (JsonNode t : MAPPER.readTree(body(mvc.perform(get("/api/catalog/tables"))))) {
            stored.add(t.get("fullName").asText());
        }
        assertFalse(stored.contains("default.tmp.bb"),
                "保存必须过滤临时表，库里出现 tmp.bb 说明没接上: " + stored);
        assertTrue(stored.contains("default.dc_sink.sink_b"), stored.toString());

        // SQL 里写的是两段名 dc_sink.sink_b，入库必须补成三段 ——
        // 两段名与元数据侧的三段全名关联不上，页面上就取不到中文名和表类型
        for (String name : stored) {
            assertEquals(3, name.split("\\.").length,
                    "库里的表全名必须恒为 目录.库.表 三段: " + name);
        }
    }

    /**
     * 保存永远过滤，不受「包含临时表」开关影响。
     *
     * <p>开关是展示偏好，「库里不存临时表」是数据约束。两者混同的话，
     * 用户勾一下开关就能把临时表灌进库里，而血缘关系页对此毫无办法。
     */
    @Test
    @Order(31)
    void saveIgnoresTheIncludeTempSwitch() throws Exception {
        String sql = "create table tmp.cc as select id from dc_src.source_a; "
                + "insert into dc_sink.sink_b select id from tmp.cc";

        // 请求体里显式打开开关 —— save 接口应当忽略它
        mvc.perform(post("/api/lineage/save").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "dbType", "hive", "querySql", sql, "includeTemp", true))))
                .andExpect(status().isOk());

        for (JsonNode t : MAPPER.readTree(body(mvc.perform(get("/api/catalog/tables"))))) {
            assertFalse("default.tmp.cc".equals(t.get("fullName").asText()),
                    "save 不该理会 includeTemp");
        }
    }

    /**
     * 血缘侧的表要能关联到元数据侧，把中文名带出来。
     *
     * <p>这条曾经是坏的：元数据侧走默认目录恒为三段 {@code default.dc_join.orders}，
     * 而血缘侧直接用 SQL 里的两段名 {@code dc_join.orders} 落库，
     * 两边靠 {@code full_name} 关联，永远对不上 —— 表基础信息页和全局搜索里，
     * 凡是血缘产生的表一律显示不出中文名和表类型。
     *
     * <p>现在解析阶段就把名字补成三段，两边自然对齐。
     */
    @Test
    @Order(32)
    void lineageTableJoinsMetadataAndPicksUpChineseName() throws Exception {
        ddlInto("default", "create table dc_join.orders (id bigint comment '订单号') "
                + "comment '订单主表'");
        ddlInto("default", "create table dc_join.detail (id bigint comment '明细号') "
                + "comment '订单明细'");

        // SQL 里写的是两段名，用户日常就是这么写的
        mvc.perform(post("/api/lineage/save").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "dbType", "hive",
                                "querySql", "insert into dc_join.detail select id from dc_join.orders"))))
                .andExpect(status().isOk());

        JsonNode hit = null;
        for (JsonNode t : MAPPER.readTree(body(mvc.perform(get("/api/catalog/tables"))))) {
            if ("default.dc_join.detail".equals(t.get("fullName").asText())) {
                hit = t;
            }
        }
        assertTrue(hit != null, "血缘保存后应能在表目录里查到 default.dc_join.detail");
        assertEquals("订单明细", hit.get("comment").asText(),
                "血缘表没能关联上元数据 —— 两边 full_name 段数不一致就会这样");
    }

    /**
     * 最初那个 bug：元数据在<b>非默认目录</b>、SQL 只写两段名时，页面上取不到中文名。
     *
     * <p>血缘侧按默认目录补全成 {@code default.dc_mm.detail}，元数据却挂在
     * {@code cat_mismatch} 下，两边 {@code full_name} 首段不同 ——
     * 1.0.5 之前靠 {@code left join ... on full_name} 关联，注定失败。
     *
     * <p>现在描述在解析时就从那个元数据来源快照下来随血缘落库，与目录段无关。
     */
    @Test
    @Order(33)
    void descriptionsSurviveWhenMetadataLivesInANonDefaultCatalog() throws Exception {
        mvc.perform(post("/api/data-catalogs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"cat_mismatch\"}")).andExpect(status().isOk());
        ddlInto("cat_mismatch", "create table dc_mm.orders "
                + "(id bigint comment '订单号', amount decimal(12,2) comment '金额') comment '订单主表'");
        ddlInto("cat_mismatch", "create table dc_mm.detail "
                + "(id bigint comment '明细号') comment '订单明细'");

        // 两段名 + select *：后者必须靠元数据展开列，能存下来就说明解析确实用上了
        // cat_mismatch 下那张表
        mvc.perform(post("/api/lineage/save").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "dbType", "hive", "sourceId", 1,
                                "querySql", "insert into dc_mm.detail select id from dc_mm.orders"))))
                .andExpect(status().isOk());

        JsonNode table = catalogTable("default.dc_mm.orders");
        assertTrue(table != null, "血缘目录里应有 default.dc_mm.orders");
        assertEquals("订单主表", table.get("comment").asText(),
                "描述该由解析时用的那个元数据来源带过来，与它挂在哪个目录无关");

        // 字段的类型与中文名同样要带上
        JsonNode idColumn = null;
        for (JsonNode c : MAPPER.readTree(body(mvc.perform(
                get("/api/catalog/tables/" + table.get("id").asLong() + "/columns"))))) {
            if ("id".equals(c.get("columnName").asText())) {
                idColumn = c;
            }
        }
        assertTrue(idColumn != null, "应能查到 id 字段");
        assertEquals("订单号", idColumn.get("comment").asText());
        assertEquals("bigint", idColumn.get("dataType").asText(), "字段类型也要快照下来");
    }

    /**
     * 重新解析保存一次，描述跟着刷新。
     *
     * <p>这是快照方案唯一的更新途径 —— upsert 不能只插不更，否则第一次存下来是什么样，
     * 之后永远是什么样。
     */
    @Test
    @Order(34)
    void resavingRefreshesTheSnapshot() throws Exception {
        ddlInto("default", "create table dc_rs.src (id bigint comment '旧字段名') comment '旧表名'");

        String sql = "insert into dc_rs.sink select id from dc_rs.src";
        saveLineage(sql);
        assertEquals("旧表名", catalogTable("default.dc_rs.src").get("comment").asText());

        // 改元数据里的中文名，再存一次
        long metaId = metaTableIdOf("default.dc_rs.src");
        mvc.perform(put("/api/meta/tables/" + metaId).contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("comment", "新表名"))))
                .andExpect(status().isOk());
        saveLineage(sql);

        assertEquals("新表名", catalogTable("default.dc_rs.src").get("comment").asText(),
                "重新解析保存后快照应当刷新");
    }

    /**
     * 这次解析没拿到元数据时，<b>不能</b>把上次存的描述冲掉。
     *
     * <p>upsert 里那个 {@code coalesce(?, 列)} 就是为这条服务的。写成直接赋值的话，
     * 用户会看到中文名莫名其妙消失。
     */
    @Test
    @Order(35)
    void reparseWithoutMetadataDoesNotWipeExistingDescription() throws Exception {
        ddlInto("default", "create table dc_keep.src (id bigint comment '要保住的字段名') "
                + "comment '要保住的表名'");

        String sql = "insert into dc_keep.sink select id from dc_keep.src";
        saveLineage(sql);
        assertEquals("要保住的表名", catalogTable("default.dc_keep.src").get("comment").asText());

        // 把元数据删掉，再存一次 —— 这次任何来源都给不出描述
        mvc.perform(delete("/api/meta/tables/" + metaTableIdOf("default.dc_keep.src")))
                .andExpect(status().isOk());
        saveLineage(sql);

        assertEquals("要保住的表名", catalogTable("default.dc_keep.src").get("comment").asText(),
                "拿不到描述时应保留旧值，而不是用 null 覆盖");
    }

    /**
     * 在元数据页手工改中文名，血缘页要<b>当场</b>看到。
     *
     * <p>描述是快照，正常靠重新解析保存刷新。但「刚在这张表上点了保存」是明确的意图 ——
     * 不写穿的话，用户会觉得那个保存按钮坏了：改完回到血缘页还是旧名字。
     */
    @Test
    @Order(36)
    void editingMetadataWritesThroughToTheLineageSnapshot() throws Exception {
        ddlInto("default", "create table dc_wt.src (id bigint comment '原字段名') comment '原表名'");
        saveLineage("insert into dc_wt.sink select id from dc_wt.src");
        assertEquals("原表名", catalogTable("default.dc_wt.src").get("comment").asText());

        mvc.perform(put("/api/meta/tables/" + metaTableIdOf("default.dc_wt.src"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("comment", "手工改的名字"))))
                .andExpect(status().isOk());

        assertEquals("手工改的名字", catalogTable("default.dc_wt.src").get("comment").asText(),
                "手工改完描述，血缘页要当场看到，不能等到下次重新解析");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void saveLineage(String sql) throws Exception {
        mvc.perform(post("/api/lineage/save").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "dbType", "hive", "sourceId", 1, "querySql", sql))))
                .andExpect(status().isOk());
    }

    /** 血缘目录里按全名找一张表，找不到返回 null。 */
    private JsonNode catalogTable(String fullName) throws Exception {
        for (JsonNode t : MAPPER.readTree(body(mvc.perform(get("/api/catalog/tables"))))) {
            if (fullName.equals(t.get("fullName").asText())) {
                return t;
            }
        }
        return null;
    }

    private long metaTableIdOf(String fullName) throws Exception {
        JsonNode page = MAPPER.readTree(body(mvc.perform(
                get("/api/meta/tables").param("size", "500"))));
        for (JsonNode t : page.path("items")) {
            if (fullName.equals(t.get("fullName").asText())) {
                return t.get("id").asLong();
            }
        }
        throw new AssertionError("元数据目录里没有 " + fullName);
    }

    private JsonNode analyze(String sql, boolean includeTemp) throws Exception {
        return MAPPER.readTree(body(mvc.perform(post("/api/lineage/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "dbType", "hive", "querySql", sql,
                                "sourceId", 1, "includeTemp", includeTemp))))
                .andExpect(status().isOk())));
    }

    /** 图上出现的表名。节点是字段（{@code schema.table.column}），去掉列名一段。 */
    private static Set<String> graphTables(JsonNode graph) {
        Set<String> tables = new LinkedHashSet<>();
        for (JsonNode node : graph.path("data").path("withProcessData").path("data")) {
            addTable(tables, node.path("targetField").path("fieldName").asText(""));
            for (JsonNode ref : node.path("refFields")) {
                addTable(tables, ref.path("fieldName").asText(""));
            }
        }
        return tables;
    }

    private static void addTable(Set<String> tables, String fieldName) {
        int dot = fieldName.lastIndexOf('.');
        if (dot > 0) {
            tables.add(fieldName.substring(0, dot));
        }
    }

    /** 图上是否存在「下游表 ← 上游表」的连边（不关心具体是哪个字段）。 */
    private static boolean hasEdge(JsonNode graph, String targetTable, String sourceTable) {
        for (JsonNode node : graph.path("data").path("withProcessData").path("data")) {
            if (!node.path("targetField").path("fieldName").asText("")
                    .startsWith(targetTable + ".")) {
                continue;
            }
            for (JsonNode ref : node.path("refFields")) {
                if (ref.path("fieldName").asText("").startsWith(sourceTable + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isTemp(String name) throws Exception {
        return MAPPER.readTree(body(
                mvc.perform(get("/api/data-catalogs/temp-rules/test?name=" + name))))
                .get("temp").asBoolean();
    }

    private long createRule(String target, String matchType, String pattern) throws Exception {
        return MAPPER.readTree(body(mvc.perform(post("/api/data-catalogs/temp-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "target", target, "matchType", matchType, "pattern", pattern))))
                .andExpect(status().isOk()))).get("id").asLong();
    }

    private void ddlInto(String catalog, String ddl) throws Exception {
        mvc.perform(post("/api/meta/ddl").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "dbType", "hive", "catalogName", catalog, "ddl", ddl))))
                .andExpect(status().isOk());
    }

    private long catalogIdByName(String name) throws Exception {
        for (JsonNode c : MAPPER.readTree(body(mvc.perform(get("/api/data-catalogs"))))) {
            if (name.equals(c.get("name").asText())) {
                return c.get("id").asLong();
            }
        }
        throw new IllegalStateException("没找到数据目录: " + name);
    }

    private String fullNameOf(String tableName) throws Exception {
        for (JsonNode t : MAPPER.readTree(body(mvc.perform(get("/api/meta/tables?size=200"))))
                .path("items")) {
            if (tableName.equals(t.get("tableName").asText())) {
                return t.get("fullName").asText();
            }
        }
        throw new IllegalStateException("元数据里没找到表: " + tableName);
    }

    private int countTables(String tableName) throws Exception {
        int n = 0;
        for (JsonNode t : MAPPER.readTree(body(mvc.perform(get("/api/meta/tables?size=200"))))
                .path("items")) {
            if (tableName.equals(t.get("tableName").asText())) {
                n++;
            }
        }
        return n;
    }

    /** MockMvc 默认按 ISO-8859-1 解码，中文断言会全部错开，必须显式指定 UTF-8。 */
    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
