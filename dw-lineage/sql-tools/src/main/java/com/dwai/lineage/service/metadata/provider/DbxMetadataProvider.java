package com.dwai.lineage.service.metadata.provider;

import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.sqlflow.metadata.SchemaTable;
import com.dwai.lineage.service.metadata.dbx.DbxClient;
import com.dwai.lineage.service.metadata.dbx.DbxColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从 dbx 读取表结构。
 *
 * <p>与 {@link GravitinoMetadataProvider} 并列的一种外部元数据来源，遵守
 * {@link MetadataProvider} 的既有约定：批量入参、单表查不到只进 {@code unresolved} 不抛异常。
 *
 * <p><b>覆盖面</b>：dbx 是通用数据库管理工具，OLTP 与 OLAP 都覆盖，
 * 通过原生 + Agent 驱动支持 80+ 种数据库
 * （MySQL / PostgreSQL / ClickHouse / Doris / StarRocks / Oracle / TiDB …，
 * Agent 方式还可扩展到 Hive / Trino / Databricks / Snowflake 等），
 * 具体能连什么由 dbx 那边决定，本类不做类型判断。
 *
 * <p><b>已知限制</b>：实测其 {@code /api/schema/columns} 的字段列表里没有分区标记，
 * 因此本 provider 取不到分区列。分区列对 Hive / Spark 血缘是必需的
 * （一期修过的 {@code Column 'pt' not found} 正是这类问题），
 * 所以解析结果里会附一条 warning；分区表建议用能直连 HMS 的 Gravitino 交叉验证。
 *
 * <p>不做并发：dbx 通常是单机小服务，且 schema 接口要求连接处于已连接状态，
 * 并发打过去只会互相干扰，收益也不像 Gravitino 那样明显。
 */
public class DbxMetadataProvider implements MetadataProvider {

    private static final Logger logger = LoggerFactory.getLogger(DbxMetadataProvider.class);

    private static final String PARTITION_WARNING =
            "表结构来自 dbx，其中不含分区列；若 SQL 涉及 Hive / Spark 分区表，"
                    + "分区字段可能缺失，建议用 Gravitino 交叉验证";

    private final DbxClient client;
    private final String connectionId;
    private final String database;
    private final String defaultSchema;

    /** 连接只建立一次，后续表复用。 */
    private boolean connected;

    public DbxMetadataProvider(DbxClient client, String connectionId, String database, String defaultSchema) {
        this.client = client;
        this.connectionId = connectionId;
        this.database = database;
        this.defaultSchema = (defaultSchema == null || defaultSchema.isBlank()) ? null : defaultSchema;
    }

    @Override
    public String name() {
        return "dbx";
    }

    @Override
    public MetadataResolution resolve(Set<QualifiedObjectName> tables) {
        if (tables == null || tables.isEmpty()) {
            return MetadataResolution.empty();
        }

        Map<QualifiedObjectName, SchemaTable> resolved = new LinkedHashMap<>();
        Map<QualifiedObjectName, TableDescriptor> descriptors = new LinkedHashMap<>();
        Set<QualifiedObjectName> unresolved = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();

        try {
            ensureConnected();
        } catch (Exception e) {
            // 整个来源不可用：交回全部未解析，由 CompositeMetadataProvider 降级到下一个来源
            logger.warn("dbx 连接失败，本次不使用该来源: {}", e.getMessage());
            return new MetadataResolution(Map.of(), tables,
                    List.of("dbx 不可用: " + e.getMessage()));
        }

        for (QualifiedObjectName wanted : tables) {
            String schema = schemaOf(wanted);
            try {
                // 用 columnDetails 而不是 columns：两者打的是同一个
                // /api/schema/columns，只是后者把 data_type 和 comment 丢了。
                // 换成这个不增加任何请求，却能把描述一并带出来随血缘落库
                List<DbxColumn> columns =
                        client.columnDetails(connectionId, database, schema, wanted.getObjectName());
                if (columns.isEmpty()) {
                    unresolved.add(wanted);
                } else {
                    List<String> names = new ArrayList<>();
                    Map<String, ColumnDescriptor> columnDescriptors = new LinkedHashMap<>();
                    for (DbxColumn c : columns) {
                        names.add(c.name());
                        columnDescriptors.put(TableDescriptor.normalizeColumnKey(c.name()),
                                ColumnDescriptor.of(c.dataType(), c.comment()));
                    }
                    // 分区列一律传空：dbx 拿不到，硬编一个空列表比编造字段安全。
                    // 必须走 SchemaTables：空 catalog 要归一成 null，否则分析器按字符串匹配时找不到
                    resolved.put(wanted, SchemaTables.of(
                            wanted, schema, wanted.getObjectName(), names, List.of()));
                    // dbx 的这个接口不返回表注释，只有列级描述
                    descriptors.put(wanted,
                            new TableDescriptor(null, null, null, columnDescriptors));
                }
            } catch (Exception e) {
                unresolved.add(wanted);
                warnings.add(describeTableFailure(wanted, e));
                logger.debug("dbx 获取表结构失败: {}", wanted, e);
            }
        }

        if (!resolved.isEmpty()) {
            warnings.add(PARTITION_WARNING);
        }
        return new MetadataResolution(resolved, unresolved, warnings, descriptors);
    }

    private void ensureConnected() {
        if (connected) {
            return;
        }
        if (!client.connectIfSaved(connectionId)) {
            // 没在「已保存的连接」里找到，不代表用不了：dbx 的连接是有状态的，
            // 一个只在其界面上连过、并未保存的连接同样能被 schema 接口使用。
            // 这里不失败，把判定交给后续的 schema 调用 —— 那时的报错更贴近真实原因。
            logger.info("dbx 中没有名为「{}」的已保存连接，按「该连接已处于连接状态」继续尝试", connectionId);
        }
        connected = true;
    }

    /**
     * 连接压根不存在时 dbx 只回一句 {@code Connection config not found}，
     * 用户看不出该去哪儿改。这里补上可选的连接 id。
     */
    private String describeTableFailure(QualifiedObjectName wanted, Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (!message.contains("Connection config not found")) {
            return "dbx 未能获取表结构 " + wanted + ": " + message;
        }
        String available;
        try {
            available = String.valueOf(client.savedConnectionIds());
        } catch (Exception ignored) {
            available = "(读取失败)";
        }
        return "dbx 中找不到连接「" + connectionId + "」，请检查该元数据服务 extraConfig 里的 connectionId。"
                + "dbx 中已保存的连接: " + available;
    }

    /** SQL 里没写 schema 时，退回配置里的默认 schema。 */
    private String schemaOf(QualifiedObjectName wanted) {
        String schema = wanted.getSchemaName();
        if (schema == null || schema.isEmpty()) {
            return defaultSchema;
        }
        return schema;
    }
}
