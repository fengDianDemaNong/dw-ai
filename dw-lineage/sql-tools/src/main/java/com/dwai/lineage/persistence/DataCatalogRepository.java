package com.dwai.lineage.persistence;

import com.dwai.lineage.tenant.LineageContext;

import java.util.List;
import java.util.Optional;

/**
 * 数据目录（{@code data_catalog}）与临时库表规则（{@code temp_rule}）的读写。
 *
 * <p>两者放在一个仓储里：它们同属「项目的元数据配置」，规则的作用域字段
 * {@code catalog_name} 指向的就是这里的目录，配置页面也是一个页面里的上下两块。
 *
 * <p><b>租户约束</b>：首参固定 {@link LineageContext}，实现里每条 SQL 都带
 * {@code tenant_id} 与 {@code project_id}，由 {@code TenantIsolationArchTest} 兜底。
 */
public interface DataCatalogRepository {

    // ==== 数据目录 ====

    List<DataCatalogRow> listCatalogs(LineageContext ctx);

    Optional<DataCatalogRow> findCatalog(LineageContext ctx, long id);

    Optional<DataCatalogRow> findCatalogByName(LineageContext ctx, String name);

    /**
     * 当前项目的默认目录。
     *
     * <p>返回 {@link Optional} 而不是直接给一个值：库里可能因为升级脚本没跑完
     * 而没有默认目录，让调用方决定是报错还是补建，比在这里编一个出来诚实。
     */
    Optional<DataCatalogRow> findDefaultCatalog(LineageContext ctx);

    long createCatalog(LineageContext ctx, DataCatalogRow row);

    /** 只改名称与说明；是否默认走 {@link #setDefaultCatalog}。 */
    int updateCatalog(LineageContext ctx, long id, String name, String description);

    /** 把 id 设为唯一默认：先把该项目其它目录的标记清掉，再设这一条。 */
    void setDefaultCatalog(LineageContext ctx, long id);

    int deleteCatalog(LineageContext ctx, long id);

    /** 该目录下已登记的表数量，用于删除前的拦截与页面展示。 */
    int countTables(LineageContext ctx, String catalogName);

    // ==== 临时库表规则 ====

    List<TempRuleRow> listRules(LineageContext ctx);

    /** 只取启用的规则，供匹配器使用。 */
    List<TempRuleRow> listEnabledRules(LineageContext ctx);

    Optional<TempRuleRow> findRule(LineageContext ctx, long id);

    long createRule(LineageContext ctx, TempRuleRow row);

    int updateRule(LineageContext ctx, long id, TempRuleRow row);

    int deleteRule(LineageContext ctx, long id);
}
