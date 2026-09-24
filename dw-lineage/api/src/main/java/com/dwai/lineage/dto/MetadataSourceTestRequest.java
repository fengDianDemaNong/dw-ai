package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 测试连接。支持两种用法：
 *
 * <ul>
 *   <li><b>试已保存的</b>：只传 {@code id}，用库里那条配置（凭据从库中解密，前端不必回传）</li>
 *   <li><b>试还没保存的</b>：传 {@code type + baseUrl + credential}，用于用户填完表单先试一下再保存</li>
 * </ul>
 *
 * <p>同时传 {@code id} 与表单字段时以表单为准，但 {@code credential} 留空则回退到库中已存的凭据 ——
 * 对应「只改地址、不重输密码」这个很常见的操作。
 */
@Schema(description = "测已保存配置只传 id；测未保存表单传 type+baseUrl+credential。")
public record MetadataSourceTestRequest(
        @Schema(description = "已保存配置 id")
        Long id,
        @Schema(description = "GRAVITINO 或 DBX")
        String type,
        @Schema(description = "服务地址")
        String baseUrl,
        @Schema(description = "凭据；与 id 同时传且留空则回退库中原值")
        String credential,
        @Schema(description = "差异化 JSON")
        String extraConfig) {
}
