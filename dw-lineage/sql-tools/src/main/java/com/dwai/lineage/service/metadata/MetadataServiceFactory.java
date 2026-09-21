package com.dwai.lineage.service.metadata;

import io.github.melin.superior.common.relational.Statement;
import org.apache.gravitino.Catalog;
import com.dwai.lineage.enums.DatabaseTypeEnum;
import com.dwai.lineage.exception.SqlParseException;
import com.dwai.lineage.service.metadata.provider.DdlMetadataProvider;
import com.dwai.lineage.service.metadata.provider.GravitinoMetadataProvider;
import com.dwai.lineage.service.metadata.provider.InferredMetadataProvider;
import com.dwai.lineage.service.metadata.provider.MetadataProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按方言拆分 SQL，并把血缘解析主流程与元数据来源组装起来。
 *
 * <p>解析流程本身在 {@link LineageAnalysisPipeline}，这里只负责三件事：
 * 选方言、决定用哪些 {@link MetadataProvider}、以及它们的优先级。
 *
 * <p>此前每种元数据来源各自复制一份完整解析流程（共四份，约 360 行），
 * 新增来源必须改动主流程；现在来源以 provider 形式注入，流程只有一份。
 */
@Component
public class MetadataServiceFactory {

    private final LineageAnalysisPipeline pipeline = new LineageAnalysisPipeline();

    private final GravitinoExecutor gravitinoExecutor;

    @Autowired
    public MetadataServiceFactory(GravitinoExecutor gravitinoExecutor) {
        this.gravitinoExecutor = gravitinoExecutor;
    }

    /**
     * 直接 new 出来用的构造器，供测试与 CLI 使用。
     *
     * <p>这两种场景没有 Spring 容器，也用不到 Gravitino，
     * 让它们各自去装配一个执行器只是噪音。
     */
    public MetadataServiceFactory() {
        this(new GravitinoExecutor());
    }

    // ------------------------------------------------------------------
    // 方言拆分
    // ------------------------------------------------------------------

    public List<Statement> statements(String databaseType, String sqlStr) {
        if (databaseType == null) {
            throw new SqlParseException("数据库类型不能为空");
        }
        // 方言与解析器的对应关系收敛在 DatabaseTypeEnum 中，新增方言不必改这里
        return DatabaseTypeEnum.fromString(databaseType).parse(sqlStr);
    }

    // ------------------------------------------------------------------
    // 血缘解析
    // ------------------------------------------------------------------

    /**
     * 用 SQL 自带的 DDL（可选）+ 结构推断作为元数据来源。
     *
     * @param useCreateTable 是否把 SQL 中的 CREATE TABLE 作为表结构来源
     */
    public LineageAnalysisPipeline.LineageResult analyze(String databaseType,
                                                         boolean useCreateTable,
                                                         String sqlStr) {
        return analyze(databaseType, useCreateTable, sqlStr, null);
    }

    /**
     * 在 DDL 与推断之间插入一个外部元数据来源。
     *
     * <p>优先级固定为 <b>SQL 内 DDL &gt; 外部来源 &gt; 结构推断</b>：
     * 脚本里显式写了建表语句就以它为准，外部来源查不到的表退化为推断，
     * 尽量给出部分血缘而不是整个请求失败。
     *
     * @param external 外部来源，可为 null（等同于不接外部元数据）。
     *                 多个来源的串联由调用方用 {@code CompositeMetadataProvider} 组好后传进来
     */
    public LineageAnalysisPipeline.LineageResult analyze(String databaseType,
                                                         boolean useCreateTable,
                                                         String sqlStr,
                                                         MetadataProvider external) {
        List<Statement> statements = statements(databaseType, sqlStr);
        return pipeline.analyze(statements, (stmts, flowStmts) -> {
            MetadataProvider ddl = useCreateTable ? new DdlMetadataProvider(stmts) : null;
            return LineageAnalysisPipeline.chain(ddl, external, new InferredMetadataProvider(flowStmts));
        });
    }

    /**
     * 用 Gravitino 的某个 catalog 作为主要元数据来源。
     *
     * <p>方言从 catalog 的 provider 字符串推断（形如 {@code jdbc-mysql}），
     * 因此这个入口不需要调用方再传 dbType。
     */
    public LineageAnalysisPipeline.LineageResult analyze(Catalog catalog, String sqlStr) {
        String databaseType = dialectOf(catalog);
        List<Statement> statements = statements(databaseType, sqlStr);

        return pipeline.analyze(statements, (stmts, flowStmts) -> LineageAnalysisPipeline.chain(
                new DdlMetadataProvider(stmts),
                gravitinoProvider(catalog),
                new InferredMetadataProvider(flowStmts)));
    }

    /** 按 catalog 构造 Gravitino provider，线程池与超时统一取自 {@link GravitinoExecutor}。 */
    public GravitinoMetadataProvider gravitinoProvider(Catalog catalog) {
        return new GravitinoMetadataProvider(catalog,
                gravitinoExecutor.executor(), gravitinoExecutor.perTableTimeoutSeconds());
    }

    /**
     * 从 Gravitino catalog 的 provider 推断方言。
     *
     * <p>provider 形如 {@code jdbc-mysql} / {@code lakehouse-iceberg}，取后半段。
     * 格式不符时给出可读报错，而不是让 {@code split} 数组越界抛 AIOOBE。
     */
    public static String dialectOf(Catalog catalog) {
        String provider = catalog.provider();
        if (provider == null || !provider.contains("-")) {
            throw new SqlParseException(
                    "无法从 Gravitino catalog 推断数据库方言, provider=" + provider
                            + "。请在解析请求中显式指定 dbType");
        }
        return provider.substring(provider.indexOf('-') + 1);
    }

    // ---- 兼容既有调用方：只取血缘字符串 ----

    public List<String> getLineageStrList(String databaseType, boolean useCreateTable, String sqlStr) {
        return analyze(databaseType, useCreateTable, sqlStr).lineages();
    }

    public List<String> getLineageStrList(Catalog catalog, String sqlStr) {
        return analyze(catalog, sqlStr).lineages();
    }
}
