package com.dwai.lineage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 血缘图，接口的响应体。
 *
 * <p>此前 service 层直接返回拼好的 JSON 字符串，导致无法结构化断言、无法复用、
 * 也无法生成接口文档；改为领域对象后由 Spring 统一序列化。
 *
 * <p>{@code code} 与 {@code data} 的结构保持不变以兼容既有前端渲染逻辑；
 * 原先的 {@code errno} / {@code error} / {@code request_id} 由
 * {@code message} / {@code traceId} 取代。
 *
 * @param code             0 表示成功
 * @param data             血缘数据
 * @param warnings         告警，例如检测到环、某个元数据来源不可用
 * @param failedStatements 解析失败的语句及原因（部分失败容忍）
 * @param unresolvedTables 结构未知的表，{@code select *} 等场景会因此缺列
 * @param message          面向用户的提示
 * @param traceId          日志关联 id
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LineageGraph(int code,
                           Data data,
                           List<String> warnings,
                           List<FailedStatement> failedStatements,
                           List<String> unresolvedTables,
                           String message,
                           String traceId) {

    /**
     * @param withProcessData 保留全部中间过程（临时表、子查询）
     * @param noProcessData   压平中间过程，最终产出字段直连根源字段
     */
    public record Data(Section withProcessData, Section noProcessData) {}

    /**
     * @param size  条目数
     * @param level 最深层级
     */
    public record Section(List<Item> data, int size, int level) {}

    /** 一个目标字段及其上游。 */
    public record Item(Field targetField, List<Field> refFields) {}

    /**
     * 血缘图中的一个字段节点。
     *
     * @param fieldName 全限定字段名 {@code schema.table.column}
     * @param isFinal   是否为根源字段（没有上游）。序列化为 {@code final}
     * @param index     同层内的表序号，同一张表的字段共享
     * @param level     层级，最终产出字段为 0，每向上游一跳 +1
     * @param cyclic    该字段是否落在环上，不在环上时不输出该字段
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Field(String fieldName,
                        @JsonProperty("final") boolean isFinal,
                        int index,
                        int level,
                        Boolean cyclic) {}
}
