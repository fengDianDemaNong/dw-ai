package com.dwai.lineage.persistence;

import com.dwai.lineage.enums.MatchType;
import com.dwai.lineage.enums.TempRuleTarget;

import java.time.LocalDateTime;

/**
 * 一条临时库/表规则。
 *
 * @param catalogName 只对该数据目录生效；为空表示该项目下所有目录
 * @param target      作用在库名还是表名上
 * @param matchType   通配符还是正则 —— 两者对 {@code tmp_*} 的解释完全不同，见 {@link MatchType}
 */
public record TempRuleRow(long id,
                          long tenantId,
                          long projectId,
                          String catalogName,
                          TempRuleTarget target,
                          MatchType matchType,
                          String pattern,
                          boolean enabled,
                          String description,
                          LocalDateTime createdAt,
                          LocalDateTime updatedAt) {

    public static TempRuleRow of(String catalogName, TempRuleTarget target, MatchType matchType,
                                 String pattern, boolean enabled, String description) {
        return new TempRuleRow(0, 0, 0, catalogName, target, matchType, pattern,
                enabled, description, null, null);
    }
}
