package com.dwai.lineage.dto;

/**
 * 测试连接的结果。
 *
 * <p>失败不当成 HTTP 错误：连不上是这个接口的正常结果之一，前端要拿到原因显示在表单旁边，
 * 而不是弹一个全局错误提示。
 *
 * @param message 面向用户的描述。<b>不得包含任何凭据</b>
 */
public record MetadataSourceTestResponse(boolean success, String message) {

    public static MetadataSourceTestResponse ok(String message) {
        return new MetadataSourceTestResponse(true, message);
    }

    public static MetadataSourceTestResponse fail(String message) {
        return new MetadataSourceTestResponse(false, message);
    }
}
