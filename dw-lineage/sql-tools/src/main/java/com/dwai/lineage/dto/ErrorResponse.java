package com.dwai.lineage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 统一错误响应。
 *
 * <p>{@code traceId} 与服务端日志一一对应，排查问题时让用户提供该 id 即可定位，
 * 无需把异常堆栈或内部地址暴露给前端。
 *
 * @param error     错误摘要（保留该字段名以兼容既有前端）
 * @param message   面向用户的可读提示
 * @param traceId   日志关联 id
 * @param path      出错的请求路径
 * @param timestamp 发生时间
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String error,
                            String message,
                            String traceId,
                            String path,
                            Instant timestamp) {

    public ErrorResponse(String message, String traceId, String path) {
        this(message, message, traceId, path, Instant.now());
    }
}
