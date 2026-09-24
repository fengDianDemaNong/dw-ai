package com.dwai.lineage.service.metadata.provider;

import io.github.melin.sqlflow.metadata.QualifiedObjectName;

import java.util.Set;

/**
 * 表结构元数据来源。
 *
 * <p>字段级血缘需要知道表结构，否则 {@code select *} 和不带表前缀的列名无法展开。
 * 表结构可能来自 SQL 内的 DDL、外部元数据服务（Gravitino / dbx）、或从 SQL 本身推断，
 * 本接口把这些来源统一起来，使新增来源不必改动血缘解析主流程。
 *
 * <p>实现约定：
 * <ul>
 *   <li><b>批量</b>：一次传入全部待解析的表，实现可以并发或批量请求，避免逐表往返</li>
 *   <li><b>不抛异常</b>：单张表查不到属于正常情况，放进 {@code unresolved} 即可；
 *       只有配置错误等无法继续的情况才抛异常</li>
 * </ul>
 */
public interface MetadataProvider {

    /** provider 名称，用于日志与告警文案。 */
    String name();

    /**
     * 批量解析表结构。
     *
     * @param tables 待解析的表，可能为空
     * @return 解析结果；查不到的表放入 {@link MetadataResolution#unresolved()}
     */
    MetadataResolution resolve(Set<QualifiedObjectName> tables);
}
