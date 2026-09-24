package service;

import org.junit.Test;
import com.dwai.lineage.enums.SyncStatus;
import com.dwai.lineage.persistence.SyncJobRepository;
import com.dwai.lineage.persistence.SyncJobRow;
import com.dwai.lineage.service.impl.SyncJobRunner;
import com.dwai.lineage.tenant.LineageContext;
import com.dwai.lineage.tenant.TenantContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 导入任务执行器的跨线程上下文传递。
 *
 * <p>为什么要单独测这个：任务体收到的是显式传入的 {@code LineageContext}，
 * 所以大部分代码路径即使不透传 ThreadLocal 也照样跑得通 ——
 * 光看「任务成功了」证明不了透传生效。
 * 但 {@code GravitinoMetadataServiceImp.client()} 走的是
 * {@code TenantContextHolder.require()}，不透传的话 Gravitino 导入必然失败，
 * 而这个失败只体现在任务状态里，HTTP 接口仍是 200 —— 靠端到端用例发现不了。
 *
 * <p>所以这里直接断言<b>任务线程里的 ThreadLocal 有值</b>，而不是绕一圈看任务结果。
 */
public class SyncJobRunnerTest {

    private static final LineageContext CTX = new LineageContext(7, 9);

    @Test
    public void taskThreadInheritsTenantContext() throws Exception {
        SyncJobRunner runner = new SyncJobRunner(new NoopRepository());
        AtomicReference<LineageContext> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        TenantContextHolder.set(CTX);
        try {
            runner.submit(CTX, 1L, () -> {
                seen.set(TenantContextHolder.find().orElse(null));
                done.countDown();
            });
        } finally {
            TenantContextHolder.clear();
        }

        assertTrue("任务没有在 5 秒内执行", done.await(5, TimeUnit.SECONDS));
        assertNotNull("任务线程里拿不到租户上下文 —— 提交时没有透传 ThreadLocal，"
                + "Gravitino 导入会因此全部失败", seen.get());
        assertEquals(CTX, seen.get());
    }

    /** 任务抛异常时必须被标成失败，否则它会永远停在 RUNNING、页面一直转圈。 */
    @Test
    public void failingTaskIsMarkedFailed() throws Exception {
        RecordingRepository repository = new RecordingRepository();
        SyncJobRunner runner = new SyncJobRunner(repository);

        runner.submit(CTX, 42L, () -> {
            throw new IllegalStateException("故意失败");
        });

        assertTrue("兜底没生效，任务会永远停在 RUNNING",
                repository.finished.await(5, TimeUnit.SECONDS));
        assertEquals(SyncStatus.FAILED, repository.status);
        assertEquals(42L, repository.jobId);
    }

    // ------------------------------------------------------------------

    private static class NoopRepository implements SyncJobRepository {
        @Override public long create(LineageContext ctx, SyncJobRow job) { return 0; }
        @Override public Optional<SyncJobRow> findById(LineageContext ctx, long id) { return Optional.empty(); }
        @Override public List<SyncJobRow> listRecent(LineageContext ctx, int limit) { return List.of(); }
        @Override public void markRunning(LineageContext ctx, long id) { }
        @Override public void updateTotal(LineageContext ctx, long id, int total) { }
        @Override public void updateProgress(LineageContext ctx, long id, int done, int created,
                                             int updated, int skipped, int failed) { }
        @Override public void finish(LineageContext ctx, long id, SyncStatus status, String message,
                                     List<String> failures) { }
        @Override public int failInterruptedJobs(String reason) { return 0; }
    }

    private static class RecordingRepository extends NoopRepository {
        final CountDownLatch finished = new CountDownLatch(1);
        volatile SyncStatus status;
        volatile long jobId;

        @Override
        public void finish(LineageContext ctx, long id, SyncStatus s, String message,
                           List<String> failures) {
            this.status = s;
            this.jobId = id;
            finished.countDown();
        }
    }
}
