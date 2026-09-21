package com.dwai.lineage.service.impl;

import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableCatalog;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import com.dwai.lineage.conf.GravitinoConfig;
import com.dwai.lineage.enums.MetadataSourceType;
import com.dwai.lineage.exception.SqlParseException;
import com.dwai.lineage.persistence.MetadataSourceRow;
import com.dwai.lineage.service.GravitinoMetadataServic;
import com.dwai.lineage.service.MetadataSourceService;
import com.dwai.lineage.service.metadata.GravitinoClientRegistry;
import com.dwai.lineage.service.metadata.MetadataServiceFactory;
import com.dwai.lineage.service.metadata.gravitino.GravitinoColumn;
import com.dwai.lineage.service.metadata.gravitino.GravitinoTableSchema;
import com.dwai.lineage.tenant.LineageContext;
import com.dwai.lineage.tenant.TenantContextHolder;
import com.dwai.lineage.util.SQLLineageMerger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Gravitino 元数据浏览与解析。
 *
 * <p>客户端不再是启动时建好的单例：地址现在来自 {@code metadata_source} 配置，
 * 可以有多个、可以随时改，因此统一由 {@link GravitinoClientRegistry} 按地址缓存。
 * 请求不指定 {@code sourceId} 时，退回 yml 里的 {@code gravitino.url} ——
 * 这条兜底保证了升级到本版本前的调用方式仍然可用。
 */
@Service
public class GravitinoMetadataServiceImp implements GravitinoMetadataServic {

    private static final Logger logger = LoggerFactory.getLogger(GravitinoMetadataServiceImp.class);

    private final GravitinoConfig gravitinoConfig;
    private final GravitinoClientRegistry clientRegistry;
    private final MetadataSourceService metadataSourceService;
    private final MetadataServiceFactory metadataServiceFactory;

    public GravitinoMetadataServiceImp(GravitinoConfig gravitinoConfig,
                                       GravitinoClientRegistry clientRegistry,
                                       MetadataSourceService metadataSourceService,
                                       MetadataServiceFactory metadataServiceFactory) {
        this.gravitinoConfig = gravitinoConfig;
        this.clientRegistry = clientRegistry;
        this.metadataSourceService = metadataSourceService;
        this.metadataServiceFactory = metadataServiceFactory;
    }

    @Override
    public List<String> getSupportedMateLakes(Long sourceId) {
        GravitinoMetalake[] metalakes = client(sourceId).listMetalakes();
        List<String> names = new ArrayList<>();
        for (GravitinoMetalake metalake : metalakes) {
            names.add(metalake.name());
        }
        return names;
    }

    @Override
    public List<String> getSupportedCatalogs(Long sourceId, String mateLake) {
        return Arrays.stream(client(sourceId).loadMetalake(mateLake).listCatalogs()).toList();
    }

    @Override
    public List<String> getSupportedSchemas(Long sourceId, String mateLake, String catalog) {
        Catalog loaded = client(sourceId).loadMetalake(mateLake).loadCatalog(catalog);
        return Arrays.stream(loaded.asSchemas().listSchemas()).toList();
    }

    /**
     * 某个 schema 下的表名。
     *
     * <p>只返回表名，不再拼 {@code catalog.表名,注释} —— 那个格式既丢了 schema，
     * 注释为 null 时还会变成字面量 "null"。要注释请用 {@link #getTableSchema}。
     */
    @Override
    public List<String> getSupportedTables(Long sourceId, String mateLake, String catalog, String schema) {
        List<String> tables = new ArrayList<>();
        Catalog loaded = client(sourceId).loadMetalake(mateLake).loadCatalog(catalog);
        if (loaded instanceof TableCatalog) {
            TableCatalog tableCatalog = loaded.asTableCatalog();
            for (NameIdentifier identifier : tableCatalog.listTables(Namespace.of(schema))) {
                tables.add(identifier.name());
            }
        }
        return tables;
    }

    @Override
    public GravitinoTableSchema getTableSchema(Long sourceId, String mateLake, String catalog,
                                               String schema, String table) {
        Catalog loaded = client(sourceId).loadMetalake(mateLake).loadCatalog(catalog);
        if (!(loaded instanceof TableCatalog)) {
            throw new IllegalArgumentException(
                    "Gravitino catalog「" + catalog + "」不是表类型的 catalog，无法读取表结构");
        }

        Table loadedTable = loaded.asTableCatalog().loadTable(NameIdentifier.of(schema, table));

        // 分区信息挂在表级 partitioning() 上，按列名回填到字段。
        // 分区列对 Hive / Spark 血缘是必需的（一期修过的 Column 'pt' not found 就是这个）
        Set<String> partitionNames = new LinkedHashSet<>();
        Transform[] partitioning = loadedTable.partitioning();
        if (partitioning != null) {
            for (Transform transform : partitioning) {
                partitionNames.add(transform.name());
            }
        }

        List<GravitinoColumn> columns = new ArrayList<>();
        for (Column column : loadedTable.columns()) {
            columns.add(new GravitinoColumn(
                    column.name(),
                    column.dataType() == null ? null : column.dataType().simpleString(),
                    column.comment(),
                    column.nullable(),
                    partitionNames.contains(column.name())));
        }

        return new GravitinoTableSchema(loadedTable.name(), loadedTable.comment(), columns);
    }

    @Override
    public com.dwai.lineage.dto.LineageGraph analyzeSqlLineage(Long sourceId, String mateLake, String catalog,
                                                     String columnName, String sql) {
        Catalog loadCatalog = client(sourceId).loadMetalake(mateLake).loadCatalog(catalog);
        logger.info("使用 Gravitino 解析血缘, metalake={}, catalog={}, columnFilter={}",
                mateLake, catalog, columnName);
        try {
            com.dwai.lineage.service.metadata.LineageAnalysisPipeline.LineageResult analysis =
                    metadataServiceFactory.analyze(loadCatalog, sql);
            // 部分失败与未解析的表一并回传，避免用户面对空图无从排查
            com.dwai.lineage.dto.LineageGraph result = SQLLineageMerger.buildGraph(
                    analysis.lineages(), columnName, LineageServiceImpl.toDiagnostics(analysis));
            logger.info("Gravitino 血缘解析完成");
            return result;
        } catch (IllegalArgumentException e) {
            logger.error("不支持的数据库类型: {}", e.getMessage(), e);
            throw new SqlParseException("不支持的数据库类型: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("SQL解析失败: {}", e.getMessage(), e);
            throw new SqlParseException("SQL解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 取客户端：指定了配置就用配置里的地址，否则退回 yml。
     *
     * <p>指定的配置不是 Gravitino 类型时直接报错，而不是悄悄用兜底地址 ——
     * 后者会让用户以为自己查的是选中的那个服务。
     */
    private GravitinoAdminClient client(Long sourceId) {
        if (sourceId == null) {
            return clientRegistry.client(gravitinoConfig.getUrl());
        }
        LineageContext ctx = TenantContextHolder.require();
        MetadataSourceRow row = metadataSourceService.findRow(ctx, sourceId)
                .orElseThrow(() -> new IllegalArgumentException("元数据服务配置不存在: " + sourceId));
        if (row.type() != MetadataSourceType.GRAVITINO) {
            throw new IllegalArgumentException(
                    "元数据服务「" + row.name() + "」是 " + row.type() + " 类型，不支持按 metalake / catalog 浏览");
        }
        return clientRegistry.client(row.baseUrl());
    }
}
