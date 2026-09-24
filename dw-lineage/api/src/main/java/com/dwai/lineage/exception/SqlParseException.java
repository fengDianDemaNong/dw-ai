package com.dwai.lineage.exception;

public class SqlParseException extends RuntimeException {
    public SqlParseException(String message) {
        super(message);
    }

    public SqlParseException(String message, Throwable cause) {
        super(message, cause);
    }
}