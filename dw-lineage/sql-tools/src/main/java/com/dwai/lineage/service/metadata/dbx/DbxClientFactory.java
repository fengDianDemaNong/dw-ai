package com.dwai.lineage.service.metadata.dbx;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 按地址与凭据造 {@link DbxClient}。
 *
 * <p>抽出来是因为构造它要配超时，而需要 dbx 客户端的地方已经有两处
 * （测试连接、元数据同步），超时配置散在各处迟早会不一致。
 */
@Component
public class DbxClientFactory {

    /** dbx 通常是内网服务，卡住比失败更糟，超时给短一些。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 每次新建一个，不复用。
     *
     * <p>{@link DbxClient} 持有会话 Cookie，跨请求复用会让「dbx 重启后自动重登」
     * 的判定变复杂，而登录本身很廉价。
     */
    public DbxClient create(String baseUrl, String credential) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return new DbxClient(baseUrl, credential, RestClient.builder().requestFactory(factory));
    }
}
