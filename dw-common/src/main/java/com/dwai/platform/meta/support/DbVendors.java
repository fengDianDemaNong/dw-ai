package com.dwai.platform.meta.support;

import java.util.Locale;

/**
 * 方言判定，供共享层使用。
 *
 * <p>这几个类原来住在各服务的 {@code com.dwai.platform.db.MetaDb} 里。抽 {@code dw-common}
 * 时遇到一个反向依赖：共享类型处理器需要判断「当前是不是 PostgreSQL」，但 {@code MetaDb}
 * 属于服务侧、不该被共享模块反向依赖。
 *
 * <p>解法是把「判定」本身收进共享层 —— 它只是一条字符串规则（JDBC 的 productName 里
 * 含不含 {@code postgresql}），没有任何配置或状态，不构成新的抽象负担。
 *
 * <p>服务侧原来那两份 {@code MetaDb} 后来也并进了 {@link SeedDb}（两者的差别只有 H2 默认
 * 库名一行，参数化即可），所以「Flyway 目录 / seed 脚本 / H2 库名」这些决策现在都在
 * {@code SeedDb}，本类只负责最小的一件事：判定方言。
 */
public final class DbVendors {

    private DbVendors() {
    }

    /** JDBC {@code DatabaseMetaData.getDatabaseProductName()} 是否是 PostgreSQL。 */
    public static boolean isPostgres(String productName) {
        return productName != null && productName.toLowerCase(Locale.ROOT).contains("postgresql");
    }
}
