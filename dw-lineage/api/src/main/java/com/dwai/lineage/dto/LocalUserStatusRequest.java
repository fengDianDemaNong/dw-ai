package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 启用 / 停用本地账号。
 *
 * <p>单独一个端点而不是塞进更新接口：停用是「立刻切断这个人的访问」，
 * 语义上与「改个显示名」差别很大，也会连带撤销他的刷新令牌。
 * 混在一个 PATCH 里会让「我只想改名，怎么把人踢下线了」变成可能。
 *
 * @param status 1-启用 0-停用
 */
@Schema(description = "启用或停用本地账号。")
public record LocalUserStatusRequest(

        @Schema(description = "1 启用 / 0 停用", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer status) {

    /** 缺省按停用处理 —— 这个端点的存在理由就是停用，省略字段不该被理解成启用。 */
    public int statusOrDefault() {
        return status == null ? 0 : (status == 0 ? 0 : 1);
    }
}
