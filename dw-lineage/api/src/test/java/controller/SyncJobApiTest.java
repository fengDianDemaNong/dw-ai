package controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.dwai.lineage.Main;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import support.AuthenticatedMockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 元数据导入任务的 HTTP 行为。
 *
 * <p>这里盯的是 HTTP 层的行为：提交必须<b>立刻返回</b>（做成异步的全部意义就在这）、
 * 任务落到终态而不是卡住、任务按租户隔离、非法参数在提交阶段就被拒。
 *
 * <p>跨线程的租户上下文传递<b>不在这里测</b> —— 任务体收到的是显式传入的
 * {@code LineageContext}，光看任务结果证明不了 ThreadLocal 透传生效。
 * 那一条由 {@code SyncJobRunnerTest} 直接断言任务线程里的 ThreadLocal 有值。
 */
@SpringBootTest(classes = Main.class, properties = {
        "database.type=h2",
        "database.url=jdbc:h2:mem:sync_job_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "database.username=sa",
        "database.password=",
        // 显式钉住 standard（本模块默认模式）；令牌由 @Import 的
        // AuthenticatedMockMvc 默认挂到每个请求上。见该类的说明。
        "lineage.run-mode=standard"
})
@AutoConfigureMockMvc
@Import(AuthenticatedMockMvc.class)
class SyncJobApiTest {

    static {
        persistence.TestSchema.apply(persistence.TestDataSources.h2("sync_job_test"), "h2");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 提交后立刻返回 jobId，不阻塞。
     *
     * <p>用一个连不上的 dbx 地址：导入必然失败，但那是任务的事 ——
     * 提交本身必须成功且立即返回。这恰好也验证了「失败不会把提交接口拖住」。
     */
    @Test
    void submitReturnsImmediatelyWithJobId() throws Exception {
        long sourceId = createUnreachableDbxSource("submit-fast");

        long before = System.currentTimeMillis();
        JsonNode submitted = MAPPER.readTree(body(mvc.perform(post("/api/meta/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceId\":" + sourceId + ",\"scope\":\"TABLE\","
                                + "\"database\":\"db\",\"schema\":\"public\","
                                + "\"connectionId\":\"c1\",\"tables\":[\"t1\"]}"))
                .andExpect(status().isOk())));
        long elapsed = System.currentTimeMillis() - before;

        assertNotNull(submitted.get("jobId"), "必须返回 jobId");
        assertTrue(elapsed < 3000,
                "提交耗时 " + elapsed + "ms，说明还是同步等导入完成了");
    }

    /** 任务一定会走到终态，不会卡在 PENDING / RUNNING 让页面一直转圈。 */
    @Test
    void jobAlwaysReachesATerminalState() throws Exception {
        JsonNode job = awaitFinished(submit(createUnreachableDbxSource("terminal")));
        assertTrue(job.get("finished").asBoolean(), "任务应当已结束，实际: " + job);
        assertTrue(List.of("SUCCESS", "PARTIAL", "FAILED").contains(job.get("status").asText()),
                "终态必须是 SUCCESS/PARTIAL/FAILED 之一，实际: " + job.get("status").asText());
    }

    /** 连不上源时整体判失败，而不是停在 RUNNING。 */
    @Test
    void unreachableSourceEndsAsFailed() throws Exception {
        long jobId = submit(createUnreachableDbxSource("unreachable"));
        JsonNode job = awaitFinished(jobId);
        assertEquals("FAILED", job.get("status").asText(),
                "连不上源应当整体失败，实际: " + job);
    }

    /** 任务写在自己租户名下，别的租户查不到。 */
    @Test
    void jobsAreScopedToTheirTenant() throws Exception {
        long jobId = submit(createUnreachableDbxSource("scope-check"));

        mvc.perform(get("/api/meta/sync/jobs/" + jobId)
                        .header("X-Tenant-Id", 1).header("X-Project-Id", 1))
                .andExpect(status().isOk());
        mvc.perform(get("/api/meta/sync/jobs/" + jobId)
                        .header("X-Tenant-Id", 1).header("X-Project-Id", 999))
                .andExpect(status().isBadRequest());
    }

    /** 按表导入却没选表，应当在提交阶段就被拒，而不是造出一个必然空转的任务。 */
    @Test
    void tableScopeWithoutTablesIsRejected() throws Exception {
        long sourceId = createUnreachableDbxSource("no-tables");
        mvc.perform(post("/api/meta/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceId\":" + sourceId + ",\"scope\":\"TABLE\",\"tables\":[]}"))
                .andExpect(status().isBadRequest());
    }

    /** 不能从本地目录同步自己。 */
    @Test
    void syncingFromLocalCatalogIsRejected() throws Exception {
        Long catalogId = jdbc.queryForObject(
                "select id from metadata_source where type = 'CATALOG' and tenant_id = 1",
                Long.class);
        mvc.perform(post("/api/meta/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceId\":" + catalogId + ",\"scope\":\"TABLE\","
                                + "\"tables\":[\"t\"]}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------

    /** 建一条指向不存在端口的 dbx 源：导入必然失败，正好用来测任务的失败路径。 */
    private long createUnreachableDbxSource(String name) {
        jdbc.update("insert into metadata_source(tenant_id, name, type, base_url, priority, enabled) "
                        + "values(1, ?, 'DBX', 'http://127.0.0.1:1', 100, 1)", name);
        Long id = jdbc.queryForObject(
                "select id from metadata_source where tenant_id = 1 and name = ?", Long.class, name);
        assertNotNull(id);
        return id;
    }

    private long submit(long sourceId) throws Exception {
        JsonNode r = MAPPER.readTree(body(mvc.perform(post("/api/meta/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceId\":" + sourceId + ",\"scope\":\"TABLE\","
                                + "\"database\":\"db\",\"schema\":\"public\","
                                + "\"connectionId\":\"c1\",\"tables\":[\"t1\"]}"))
                .andExpect(status().isOk())));
        return r.get("jobId").asLong();
    }

    /** 轮询到任务结束。导入是异步的，不等就读到 PENDING。 */
    private JsonNode awaitFinished(long jobId) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode job = MAPPER.readTree(body(
                    mvc.perform(get("/api/meta/sync/jobs/" + jobId))));
            if (job.get("finished").asBoolean()) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("任务 " + jobId + " 10 秒内没有结束");
    }

    /** MockMvc 默认按 ISO-8859-1 解码，中文消息会变乱码。 */
    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
