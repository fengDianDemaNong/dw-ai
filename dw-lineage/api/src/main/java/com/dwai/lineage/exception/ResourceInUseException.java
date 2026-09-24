package com.dwai.lineage.exception;

/**
 * 目标还被数据引用着，因此拒绝删除。
 *
 * <p>典型场景：删一个已经存了血缘/元数据的租户或项目。这不是参数错误（用 400 会误导用户
 * 去改请求），也不是服务故障（用 500 会让人以为系统坏了）—— 请求完全合法，只是当前状态
 * 不允许，正对应 409 Conflict。
 *
 * <p>message 必须写清「有多少数据」和「该怎么办」，它会原样透传给用户。
 */
public class ResourceInUseException extends RuntimeException {

    public ResourceInUseException(String message) {
        super(message);
    }
}
