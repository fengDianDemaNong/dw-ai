package com.dwai.lineage.service.metadata.dbx;

/**
 * dbx 调用失败。
 *
 * <p>消息面向用户，因此构造时只放 dbx 错误体里的 {@code detail} 等可读字段，
 * <b>不得</b>拼入任何凭据。
 */
public class DbxException extends RuntimeException {

    public DbxException(String message) {
        super(message);
    }

    public DbxException(String message, Throwable cause) {
        super(message, cause);
    }
}
