package com.dwai.lineage.service.metadata;

/**
 * 一个项目的数据目录策略：默认目录名 + 临时库表规则。
 *
 * <p>两者都来自「数据目录」配置页，且在血缘解析里总是<b>一起</b>用到，
 * 所以打包传递而不是各传各的。
 *
 * <h2>为什么不能只传 matcher</h2>
 * 页面上的「包含临时表」开关打开时不做临时过滤，此前的写法是传 {@code null} 匹配器。
 * 但<b>补全数据目录不能跟着一起关掉</b> —— 关掉的话图上的表名会退回两段，
 * 与保存进库的三段名不一致。
 *
 * <p>拆成两个参数就得指望每个调用方都记得「matcher 可以为 null、defaultCatalog 不行」。
 * 打包成一个对象后，不过滤临时表用 {@link #withoutTempFilter()} 表达，
 * 默认目录始终在，漏不掉。
 *
 * @param defaultCatalog 默认数据目录名，用来把两段表名补成三段
 * @param tempMatcher    临时表匹配器；{@code null} 或无规则表示不做临时过滤
 */
public record CatalogPolicy(String defaultCatalog, TempTableMatcher tempMatcher) {

    /** 什么都不做的策略：不补目录、不过滤临时表。给不需要目录上下文的调用方用。 */
    public static CatalogPolicy none() {
        return new CatalogPolicy(null, null);
    }

    /** 保留默认目录（仍然补全三段名），但不过滤临时表。对应页面上的「包含临时表」。 */
    public CatalogPolicy withoutTempFilter() {
        return new CatalogPolicy(defaultCatalog, null);
    }

    public boolean hasTempRules() {
        return tempMatcher != null && tempMatcher.hasRules();
    }

    public boolean hasDefaultCatalog() {
        return defaultCatalog != null && !defaultCatalog.isBlank();
    }
}
