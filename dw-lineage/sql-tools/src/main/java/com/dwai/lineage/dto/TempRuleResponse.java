package com.dwai.lineage.dto;

/** 一条临时库表规则。 */
public record TempRuleResponse(long id,
                               String catalogName,
                               String target,
                               String matchType,
                               String pattern,
                               boolean enabled,
                               String description) {
}
