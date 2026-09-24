package com.dwai.lineage.dto;

import java.util.List;

/**
 * 通用分页结果。
 *
 * @param total 满足条件的总数，用于前端算页码；不是本页条数
 */
public record PageResponse<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        return new PageResponse<>(items, total, page, size);
    }
}
