package com.dwai.lineage.service.metadata;

import jakarta.annotation.PreDestroy;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按地址缓存 {@link GravitinoAdminClient}。
 *
 * <p>此前客户端是在 {@code GravitinoMetadataServiceImp} 的 {@code @PostConstruct} 里
 * 用 yml 中的 {@code gravitino.url} 建一次的单例，导致地址只能有一个、改了还必须重启。
 * 元数据服务改为可在页面上配置多个之后，客户端必须能按地址动态创建。
 *
 * <p>缓存的理由：建客户端会初始化 HTTP 连接池，每次解析都新建太浪费；
 * 而地址数量由配置条数决定，天然有界，用 {@link ConcurrentHashMap} 足够，不需要淘汰策略。
 */
@Component
public class GravitinoClientRegistry {

    private static final Logger logger = LoggerFactory.getLogger(GravitinoClientRegistry.class);

    private final Map<String, GravitinoAdminClient> clients = new ConcurrentHashMap<>();

    public GravitinoAdminClient client(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Gravitino 地址不能为空");
        }
        String key = normalize(baseUrl);
        return clients.computeIfAbsent(key, url -> {
            logger.info("创建 Gravitino 客户端: {}", url);
            return GravitinoAdminClient.builder(url).build();
        });
    }

    /** 地址改动或配置删除后丢弃旧客户端，避免连接池一直占着一个已经没人用的地址。 */
    public void evict(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        GravitinoAdminClient removed = clients.remove(normalize(baseUrl));
        closeQuietly(removed);
    }

    private static String normalize(String baseUrl) {
        String trimmed = baseUrl.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @PreDestroy
    public void shutdown() {
        clients.values().forEach(GravitinoClientRegistry::closeQuietly);
        clients.clear();
    }

    private static void closeQuietly(GravitinoAdminClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            logger.debug("关闭 Gravitino 客户端失败: {}", e.getMessage());
        }
    }
}
