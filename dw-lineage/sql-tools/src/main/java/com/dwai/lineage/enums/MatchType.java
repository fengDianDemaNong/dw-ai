package com.dwai.lineage.enums;

/**
 * 临时库/表规则的匹配方式。
 *
 * <p>两种都提供是因为它们对同一个表达式的解释<b>完全不同</b>，而用户凭直觉写出来的
 * 往往是通配符。最典型的就是 {@code tmp_*}：
 *
 * <ul>
 *   <li>{@link #GLOB} 下匹配 {@code tmp_abc}、{@code tmp_123} —— 这是绝大多数人想要的</li>
 *   <li>{@link #REGEX} 下 {@code *} 修饰的是前一个字符 {@code _}，
 *       所以它匹配 {@code tmp}、{@code tmp_}、{@code tmp__}，<b>唯独匹配不到 {@code tmp_abc}</b></li>
 * </ul>
 *
 * <p>写错了不会报错，只会默默匹配不上，等到血缘图不对才发现 ——
 * 所以配置页面上必须显式选、并且提供一个能当场试的输入框。
 */
public enum MatchType {

    /** 通配符：{@code *} 任意多个字符，{@code ?} 单个字符。其余字符按字面匹配。 */
    GLOB,

    /** Java 正则，整串匹配（相当于自带首尾锚定）。 */
    REGEX;

    public static MatchType fromString(String value) {
        if (value == null || value.isBlank()) {
            return GLOB;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "不支持的匹配方式: " + value + "，可选值: GLOB（通配符）/ REGEX（正则）");
        }
    }
}
