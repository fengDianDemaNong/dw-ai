package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 手工修改字段的描述属性。
 *
 * <p>字段名不可改（同理，改了就是另一个字段）。提交后该行 {@code source} 置为 {@code MANUAL}。
 */
@Schema(description = "手工改字段描述。字段名不可改。提交后 source=MANUAL。")
public record MetaColumnUpdateRequest(

        @Schema(description = "字段类型，如 VARCHAR(64)")
        @Size(max = 128, message = "字段类型长度不能超过 128")
        String dataType,

        @Schema(description = "中文名")
        @Size(max = 512, message = "中文名长度不能超过 512")
        String comment,

        @Schema(description = "备注")
        @Size(max = 1024, message = "备注长度不能超过 1024")
        String remark) {
}
