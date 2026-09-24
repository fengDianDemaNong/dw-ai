package com.dwai.lineage.service.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code metadata_source.extra_config} 里那段 JSON 的解析结果。
 *
 * <p>各类型用到的字段不同，放在一个类型里而不是各自解析，是因为解析这段 JSON 的地方
 * 已经有三处（构造 provider、同步、浏览），每处各写一遍 {@code readTree} + 取值
 * 很容易在字段名上写岔。
 *
 * @param connectionId dbx 专用：dbx 中已保存的那个数据库连接的 id
 * @param database     dbx 专用
 * @param schema       两者都可能用，作为 SQL 里没写库名时的默认值
 * @param metalake     Gravitino 专用
 * @param catalog      Gravitino 专用
 */
public record SourceExtraConfig(String connectionId,
                                String database,
                                String schema,
                                String metalake,
                                String catalog) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final SourceExtraConfig EMPTY =
            new SourceExtraConfig(null, null, null, null, null);

    /** 解析失败当作空配置：配置错了应该在保存时就被 validate 拦下，读取路径上没必要再炸一次。 */
    public static SourceExtraConfig parse(String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isObject()) {
                return EMPTY;
            }
            return new SourceExtraConfig(
                    text(node, "connectionId"),
                    text(node, "database"),
                    text(node, "schema"),
                    text(node, "metalake"),
                    text(node, "catalog"));
        } catch (Exception e) {
            return EMPTY;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    /** 取第一个非空值，用于「请求参数优先、配置兜底」。 */
    public static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.strip();
        }
        return (fallback != null && !fallback.isBlank()) ? fallback.strip() : null;
    }
}
