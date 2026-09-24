package com.dwai.lineage.service;

import com.dwai.lineage.dto.DataCatalogRequest;
import com.dwai.lineage.dto.DataCatalogResponse;
import com.dwai.lineage.dto.TempRuleRequest;
import com.dwai.lineage.dto.TempRuleResponse;
import com.dwai.lineage.dto.TempRuleTestResponse;
import com.dwai.lineage.service.metadata.CatalogPolicy;
import com.dwai.lineage.service.metadata.TempTableMatcher;
import com.dwai.lineage.tenant.LineageContext;

import java.util.List;

/**
 * 数据目录与临时库表规则的管理。
 *
 * <p>维护一条硬不变量：<b>每个项目有且仅有一个默认数据目录</b>。
 * 建表语句导入时不指定目录就落到它，因此本地元数据里不存在「无目录」的表，
 * 表全名恒为三段 {@code catalog.schema.table}。
 */
public interface DataCatalogService {

    List<DataCatalogResponse> listCatalogs(LineageContext ctx);

    /**
     * 取默认目录名；没有就当场建一个叫 {@code default} 的。
     *
     * <p>不抛异常而是补建：这个方法在导入路径上被调用，
     * 因为一个配置缺失就让导入失败，对用户是没道理的 ——
     * 默认目录本来就该由系统保证存在。
     */
    String defaultCatalogName(LineageContext ctx);

    DataCatalogResponse createCatalog(LineageContext ctx, DataCatalogRequest request);

    DataCatalogResponse updateCatalog(LineageContext ctx, long id, DataCatalogRequest request);

    DataCatalogResponse setDefault(LineageContext ctx, long id);

    /** 默认目录、以及下面还有表的目录都不允许删。 */
    void deleteCatalog(LineageContext ctx, long id);

    /**
     * 校验目录名是否存在；空值返回默认目录名。
     *
     * <p>给导入入口用：用户传了一个不存在的目录名要立刻报错，
     * 而不是默默建出一批挂在幽灵目录下的表。
     */
    String resolveCatalogName(LineageContext ctx, String requested);

    /**
     * 目录不存在就登记一条（非默认）。
     *
     * <p>从元数据服务同步时，目标目录直接沿用源端的 catalog / 库名，
     * 用户并没有在配置页里建过它。不登记的话会出现「元数据管理里筛得到这个目录、
     * 配置页里却看不见它」，用户既没法给它配临时表规则，也搞不清它是哪来的。
     *
     * @return 规范化后的目录名
     */
    String ensureCatalog(LineageContext ctx, String name);

    // ==== 临时库表规则 ====

    List<TempRuleResponse> listRules(LineageContext ctx);

    TempRuleResponse createRule(LineageContext ctx, TempRuleRequest request);

    TempRuleResponse updateRule(LineageContext ctx, long id, TempRuleRequest request);

    void deleteRule(LineageContext ctx, long id);

    /** 配置页的试算：给一个库名或表名，说清会不会被判为临时、命中哪条规则。 */
    TempRuleTestResponse testRule(LineageContext ctx, String input);

    /** 当前项目启用中的规则编译成的匹配器，供解析与保存两条路径共用。 */
    TempTableMatcher matcher(LineageContext ctx);

    /**
     * 默认目录名 + 临时规则，一次取齐。
     *
     * <p>血缘解析两样都要用，分开取要查两趟库；而且分开传参容易漏掉默认目录，
     * 导致图上的表名退回两段。理由详见 {@link CatalogPolicy}。
     */
    CatalogPolicy policy(LineageContext ctx);
}
