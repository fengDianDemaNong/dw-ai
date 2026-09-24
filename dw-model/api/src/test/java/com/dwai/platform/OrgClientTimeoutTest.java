package com.dwai.platform;

import com.dwai.platform.internal.OrgClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组织平台「僵住」时，模块的等待必须有界。
 *
 * <p>{@code RestClient.builder()} 的默认 connect/read timeout 都是**无限**。org 进程如果
 * 只是不响应（长 GC、网络黑洞、端口仍 accept），模块每个写请求都会永久占用一个 Tomcat 线程，
 * 故障从 org 扩散成模块整体不可用 —— 而模块本该是能独立部署的。
 *
 * <p>这个用例证明超时确实生效了，而不是只写了一行配置：桩组织平台收到请求后睡 30 秒，
 * 断言调用在个位数秒内抛出 503。少了这条，「超时没被 request factory 采纳」这种
 * 静默失效没有任何东西能发现。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dwmodel_org_timeout;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.run-mode=multi",
        "dwai.security.mode=dev"
})
class OrgClientTimeoutTest {

    /** 收到任何请求都睡 30 秒，模拟「端口通但不干活」的组织平台。 */
    private static final int SLEEP_SECONDS = 30;

    private static final HttpServer SLOW_ORG = startSlowOrg();

    private static HttpServer startSlowOrg() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", OrgClientTimeoutTest::hang);
            server.setExecutor(null);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("无法启动慢速桩组织平台", e);
        }
    }

    private static void hang(HttpExchange ex) {
        try {
            Thread.sleep(Duration.ofSeconds(SLEEP_SECONDS).toMillis());
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (var out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 客户端会先超时断开，写响应失败是预期内的
        }
    }

    @DynamicPropertySource
    static void orgBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("dwai.org-base-url",
                () -> "http://127.0.0.1:" + SLOW_ORG.getAddress().getPort());
    }

    @Autowired
    private OrgClient org;

    @Test
    void authzCallFailsFastInsteadOfHangingForever() {
        long started = System.nanoTime();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> org.check("u-1", "tenant", "project", "warehouse", "model:read"),
                "组织不响应时应当以异常结束，而不是一直等");

        long seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();
        assertTrue(seconds <= 8,
                "等 " + seconds + " 秒才失败 —— 超时没生效（读超时设的是 5 秒）");
        assertTrue(ex.getMessage() == null || ex.getMessage().contains("不可用") || ex.getStatusCode().is5xxServerError(),
                "失败原因应指向「组织不可用」：" + ex.getMessage());
    }
}
