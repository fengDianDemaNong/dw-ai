package com.dwai.lineage.dto;

import java.util.List;

/**
 * SQL 语法校验结果。
 *
 * @param valid  是否通过
 * @param errors 错误列表，通过时为空
 */
public record SqlValidateResponse(boolean valid, List<SyntaxError> errors) {

    /**
     * 一处语法错误。行列号均从 1 开始，可直接用于 Monaco 的
     * {@code setModelMarkers} 打红波浪线。
     */
    public record SyntaxError(int line, int column, String message) {}

    public static SqlValidateResponse ok() {
        return new SqlValidateResponse(true, List.of());
    }

    public static SqlValidateResponse failed(List<SyntaxError> errors) {
        return new SqlValidateResponse(false, errors);
    }
}
