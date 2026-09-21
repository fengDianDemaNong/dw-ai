package controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.dwai.lineage.Main;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 概览统计两个接口的连线检查。
 *
 * <p>{@code StatsRepositoryTest} 已经把每条口径都算过一遍了，这里只钉住那一层薄壳：
 * 路径对不对、租户上下文有没有接上、序列化出来的顶层结构是不是前端约定的那几块。
 * 这几样单测一个都覆盖不到，而任何一样错了整个页面都是白的。
 *
 * <p>断言写在 JSON 上而不是 DTO 上是刻意的：契约是那份 JSON（见前端仓库
 * {@code docs/stats-api.md}），字段改名后 DTO 断言照样通过，页面却已经拿不到值了。
 */
@SpringBootTest(classes = Main.class, properties = {
        "database.type=h2",
        "database.url=jdbc:h2:mem:stats_api_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "database.username=sa",
        "database.password="
})
@AutoConfigureMockMvc
class StatsApiTest {

    /** 与其它 API 测试同理：先建表再起 Spring 上下文，Flyway 见到非空库只会 baseline，不会重复建表。 */
    static {
        persistence.TestSchema.apply(persistence.TestDataSources.h2("stats_api_test"), "h2");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与后端 StatsLimits.TREND_DAYS 对齐 —— 前端也镜像了这个常量。 */
    private static final int TREND_DAYS = 30;

    @Autowired
    private MockMvc mvc;

    /** 空库也必须 200，且每一块都在、数值是 0、数组是 []。 */
    @Test
    void projectStatsAreServedWithEveryBlockPresent() throws Exception {
        JsonNode s = MAPPER.readTree(body("/api/stats/project"));

        for (String block : new String[]{"scale", "quality", "activity", "distributions",
                "hubs", "sync", "lists"}) {
            assertTrue(s.hasNonNull(block), "缺了 " + block + " 这一块，前端会直接抛");
        }
        assertEquals(0, s.path("scale").path("lineageTables").asInt());
        assertTrue(s.path("scale").path("dataCatalogs").isInt(), "数值字段不能缺、不能是 null");
        assertTrue(s.path("activity").path("lastParseAt").isNull(), "从未解析过时是 null");
        assertTrue(s.path("lists").path("isolatedTables").isArray());
        assertEquals(0, s.path("lists").path("isolatedTables").size());
        assertEquals(TREND_DAYS, s.path("activity").path("trend").size(),
                "趋势恒为 TREND_DAYS 条，服务端补零");
        assertEquals(TREND_DAYS, s.path("sync").path("days").asInt());
    }

    /** 租户口径：项目列表是主角，建库脚本种的那条默认项目必须在。 */
    @Test
    void tenantStatsListEveryProjectOfTheTenant() throws Exception {
        JsonNode s = MAPPER.readTree(body("/api/stats/tenant"));

        assertTrue(s.path("projects").isArray());
        assertFalse(s.path("projects").isEmpty(), "默认项目也是项目，空数据不等于空列表");
        assertEquals(s.path("projects").size(), s.path("overview").path("projects").asInt());
        assertTrue(s.path("sources").hasNonNull("byType"),
                "元数据服务是租户级配置，只在这个口径里出现");
        assertEquals(TREND_DAYS, s.path("activity").path("trend").size());
    }

    /**
     * 租户口径忽略 {@code X-Project-Id}。
     *
     * <p>这是它与项目接口唯一的口径差别，接错了不会报错 —— 只会让「本租户」那一页
     * 悄悄变成另一份「当前项目」。
     */
    @Test
    void tenantStatsIgnoreTheProjectHeader() throws Exception {
        String withDefault = body("/api/stats/tenant");
        String withOther = mvc.perform(get("/api/stats/tenant").header("X-Project-Id", "999"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertEquals(MAPPER.readTree(withDefault), MAPPER.readTree(withOther));
    }

    private String body(String url) throws Exception {
        return mvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
