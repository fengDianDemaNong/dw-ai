package com.dwai.lineage.service;

import com.dwai.lineage.dto.DialectInfo;
import com.dwai.lineage.dto.LineageGraph;
import com.dwai.lineage.service.metadata.CatalogPolicy;
import com.dwai.lineage.service.metadata.provider.MetadataProvider;

import java.util.List;

public interface LineageService {

    /**
     * 获取支持的数据库类型列表
     *
     * @return 数据库类型列表
     */
    List<String> getSupportedDatabases();

    /**
     * 获取支持的方言及其能力。
     *
     * <p>相比只返回名称的 {@link #getSupportedDatabases()}，这里额外带上能力位，
     * 前端可据此提示「该方言需要外部元数据服务」。
     */
    List<DialectInfo> getDialects();

    /**
     * 分析 SQL 字段级血缘。
     *
     * @param databaseType  数据库/引擎类型
     * @param useCreateTable 是否用 SQL 中的 CREATE TABLE 作为表结构元数据来源
     * @param columnName    可选的列过滤条件，支持裸列名或 schema.table.column 全限定名
     * @param sql           待解析 SQL，可含多条语句
     * @return 血缘图
     */
    LineageGraph analyzeSqlLineage(String databaseType, boolean useCreateTable, String columnName, String sql);

    /**
     * 同上，但在 DDL 与结构推断之间插入一个外部元数据来源。
     *
     * @param external 外部来源，可为 null。由调用方按用户选中的元数据服务配置构造好传入，
     *                 使本层不必知道 Gravitino / dbx 的存在
     */
    LineageGraph analyzeSqlLineage(String databaseType, boolean useCreateTable, String columnName,
                                   String sql, MetadataProvider external);

    /**
     * 同上，并应用当前项目的数据目录策略：把两段表名补成三段，按规则穿透临时表。
     *
     * @param policy 传 null 表示两样都不做。
     *               <b>保存路径必须传完整策略</b>：库里不存临时表、表全名恒为三段
     *               都是硬约束，不能由前端开关决定。
     *               页面上勾了「包含临时表」时用 {@link CatalogPolicy#withoutTempFilter()}，
     *               它只关掉过滤、仍然补目录
     */
    LineageGraph analyzeSqlLineage(String databaseType, boolean useCreateTable, String columnName,
                                   String sql, MetadataProvider external,
                                   CatalogPolicy policy);

    /**
     * 同上，但连同<b>描述属性快照</b>一起返回，供保存路径落库。
     *
     * <p>分析接口用不到描述，走 {@link #analyzeSqlLineage} 即可 ——
     * 那些字段不进 HTTP 响应，见 {@link AnalyzedLineage}。
     *
     * <p>返回的描述 key 已经与图上的表名对齐（补成三段、转小写），
     * 调用方直接用 {@link AnalyzedLineage#descriptorOf} 取。
     */
    AnalyzedLineage analyzeDetailed(String databaseType, boolean useCreateTable, String columnName,
                                    String sql, MetadataProvider external,
                                    CatalogPolicy policy);
}
