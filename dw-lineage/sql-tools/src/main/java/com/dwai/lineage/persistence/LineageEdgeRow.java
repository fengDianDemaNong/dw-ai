package com.dwai.lineage.persistence;

/**
 * 图遍历返回的一条边。
 *
 * @param depth     距离起点的跳数，起点自身为 0
 * @param transform 转换表达式，可能为空
 * @param cyclic    该边是否落在环上
 */
public record LineageEdgeRow(String targetField,
                             String sourceField,
                             int depth,
                             String transform,
                             boolean cyclic) {
}
