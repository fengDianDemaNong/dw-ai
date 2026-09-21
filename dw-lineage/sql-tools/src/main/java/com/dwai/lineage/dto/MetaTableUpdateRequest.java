package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 手工修改表的描述属性。
 *
 * <p>只允许改这三项：库名/表名是身份，改了就是另一张表，要改请删掉重建。
 * 提交后该行 {@code source} 会被置为 {@code MANUAL}，后续同步不再覆盖它。
 *
 * @param tableType 数仓表类型，见 {@code tableTypeEnums}：
 *                  FULL / INCRE / SNAPSHOT_FULL / SNAPSHOT_INCRE / ZIPPER / ARCH
 * @param comment   中文名
 */
@Schema(description = "手工改表描述。库名表名不可改。提交后 source=MANUAL。")
public record MetaTableUpdateRequest(

        @Schema(description = "数仓表类型：FULL / INCRE / SNAPSHOT_FULL / SNAPSHOT_INCRE / ZIPPER / ARCH")
        @Size(max = 32, message = "表类型长度不能超过 32")
        String tableType,

        @Schema(description = "中文名")
        @Size(max = 512, message = "中文名长度不能超过 512")
        String comment,

        @Schema(description = "备注")
        @Size(max = 1024, message = "备注长度不能超过 1024")
        String remark) {
}
