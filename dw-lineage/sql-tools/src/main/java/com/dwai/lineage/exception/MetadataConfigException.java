package com.dwai.lineage.exception;

/**
 * 元数据服务的<b>配置</b>问题，不是服务故障，也不是用户输入错误。
 *
 * <p>典型场景：没配 {@code METADATA_SECRET_KEY} 就想保存带凭据的服务、
 * 换过密钥导致旧凭据解不开。这类问题运维改个配置就能解决，
 * 所以消息必须原样透传到前端 —— 走兜底会被替换成
 * 「服务内部错误，请联系管理员并提供 traceId」，等于把唯一有用的信息丢掉。
 *
 * <p>因此本异常的 message <b>只允许放可以给用户看的内容</b>，
 * 不得包含密钥、凭据、内网地址或堆栈细节。
 */
public class MetadataConfigException extends RuntimeException {

    public MetadataConfigException(String message) {
        super(message);
    }

    public MetadataConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
