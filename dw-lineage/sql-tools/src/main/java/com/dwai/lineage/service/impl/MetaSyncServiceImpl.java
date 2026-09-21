package com.dwai.lineage.service.impl;

import com.dwai.lineage.dto.RemoteTableDetail;
import com.dwai.lineage.dto.MetaSyncRequest;
import com.dwai.lineage.dto.MetaSyncResult;
import com.dwai.lineage.dto.SyncJobResponse;
import com.dwai.lineage.service.metadata.dbx.DbxConnectionSummary;
import com.dwai.lineage.enums.MetaSource;
import com.dwai.lineage.enums.MetadataSourceType;
import com.dwai.lineage.enums.SyncScope;
import com.dwai.lineage.enums.SyncStatus;
import com.dwai.lineage.persistence.MetaCatalogRepository;
import com.dwai.lineage.persistence.MetaColumnRow;
import com.dwai.lineage.persistence.MetaTableRow;
import com.dwai.lineage.persistence.MetadataSourceRow;
import com.dwai.lineage.persistence.SyncJobRepository;
import com.dwai.lineage.persistence.SyncJobRow;
import com.dwai.lineage.service.GravitinoMetadataServic;
import com.dwai.lineage.service.MetaSyncService;
import com.dwai.lineage.service.DataCatalogService;
import com.dwai.lineage.service.MetadataSourceService;
import com.dwai.lineage.service.metadata.MetaNames;
import com.dwai.lineage.service.metadata.SourceExtraConfig;
import com.dwai.lineage.service.metadata.dbx.DbxClient;
import com.dwai.lineage.service.metadata.dbx.DbxClientFactory;
import com.dwai.lineage.service.metadata.dbx.DbxColumn;
import com.dwai.lineage.service.metadata.gravitino.GravitinoColumn;
import com.dwai.lineage.service.metadata.gravitino.GravitinoTableSchema;
import com.dwai.lineage.tenant.LineageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 元数据同步。
 *
 * <p>只写 {@code meta_*}，不碰血缘表。
 *
 * <p>一张表失败不影响其它表：外部服务里总有权限不足、类型不支持的个别表，
 * 为一张表让整批同步回滚，用户就只能一张张试。逐表记失败原因回传。
 */
@Service
public class MetaSyncServiceImpl implements MetaSyncService {

    private static final Logger logger = LoggerFactory.getLogger(MetaSyncServiceImpl.class);

    private final MetadataSourceService sourceService;
    private final MetaCatalogRepository catalog;
    private final GravitinoMetadataServic gravitino;
    private final DbxClientFactory dbxClients;
    private final SyncJobRepository jobs;
    private final SyncJobRunner runner;
    private final DataCatalogService dataCatalogService;

    public MetaSyncServiceImpl(MetadataSourceService sourceService,
                               MetaCatalogRepository catalog,
                               GravitinoMetadataServic gravitino,
                               DbxClientFactory dbxClients,
                               SyncJobRepository jobs,
                               SyncJobRunner runner,
                               DataCatalogService dataCatalogService) {
        this.sourceService = sourceService;
        this.catalog = catalog;
        this.gravitino = gravitino;
        this.dbxClients = dbxClients;
        this.jobs = jobs;
        this.runner = runner;
        this.dataCatalogService = dataCatalogService;
    }

    // ------------------------------------------------------------------
    // 浏览
    // ------------------------------------------------------------------

    @Override
    public List<String> browse(LineageContext ctx, long sourceId, String metalake, String catalogName,
                               String database, String schema, String connectionId) {
        MetadataSourceRow source = requireSource(ctx, sourceId);
        SourceExtraConfig extra = SourceExtraConfig.parse(source.extraConfig());

        return switch (source.type()) {
            case GRAVITINO -> browseGravitino(sourceId, metalake, catalogName, schema, extra);
            case DBX -> browseDbx(source, extra, database, schema, connectionId);
            case CATALOG -> throw new IllegalArgumentException(
                    "「" + source.name() + "」就是本地目录本身，不能从它同步");
        };
    }

    @Override
    public RemoteTableDetail loadRemoteTable(LineageContext ctx, long sourceId, String metalake,
                                             String catalogName, String database, String schema,
                                             String table, String connectionId) {
        MetadataSourceRow source = requireSource(ctx, sourceId);
        SourceExtraConfig extra = SourceExtraConfig.parse(source.extraConfig());
        String tableName = require(table, "请指定要查看的表名");

        return switch (source.type()) {
            case GRAVITINO -> {
                String lake = require(SourceExtraConfig.firstNonBlank(metalake, extra.metalake()),
                        "查看 Gravitino 的表需要指定 metalake");
                String cat = require(SourceExtraConfig.firstNonBlank(catalogName, extra.catalog()),
                        "查看 Gravitino 的表需要指定 catalog");
                String sch = require(SourceExtraConfig.firstNonBlank(schema, extra.schema()),
                        "查看 Gravitino 的表需要指定库名");
                GravitinoTableSchema loaded =
                        gravitino.getTableSchema(sourceId, lake, cat, sch, tableName);
                yield toDetail(tableName, loaded.comment(), gravitinoColumns(loaded));
            }
            case DBX -> {
                String conn = requireConnectionId(source, extra, connectionId);
                String db = SourceExtraConfig.firstNonBlank(database, extra.database());
                String sch = require(SourceExtraConfig.firstNonBlank(schema, extra.schema()),
                        "查看 dbx 的表需要指定库名");
                DbxClient client = dbxClient(source);
                ensureDbxConnection(client, source, conn);
                List<DbxColumn> loaded = client.columnDetails(conn, db, sch, tableName);
                // 表注释要单独取：dbx 把它放在 /api/schema/tables 里，columns 接口没有。
                // 多打一次请求换一个真实的中文名，值得 —— 这是用户最关心的信息之一
                yield toDetail(tableName, client.tableComment(conn, db, sch, tableName),
                        dbxColumns(loaded));
            }
            case CATALOG -> throw new IllegalArgumentException(
                    "「" + source.name() + "」就是本地目录本身，直接在本地视图里看即可");
        };
    }

    /**
     * 本地行 → 只读响应。
     *
     * <p>中间过一道 {@code MetaColumnRow} 是刻意的：预览与导入因此共用同一份字段映射
     * （{@link #gravitinoColumns} / {@link #dbxColumns}），
     * 不会出现「页面上看到的字段」和「真导进来的字段」对不上。
     */
    private static RemoteTableDetail toDetail(String table, String comment,
                                              List<MetaColumnRow> columns) {
        List<RemoteTableDetail.RemoteColumn> out = new ArrayList<>();
        for (MetaColumnRow c : columns) {
            out.add(new RemoteTableDetail.RemoteColumn(c.ordinal(), c.columnName(), c.dataType(),
                    c.comment(), c.nullable(), c.partition(), c.primary()));
        }
        return new RemoteTableDetail(table, comment, out);
    }

    /** metalake → catalog → schema → table，按已填到哪一级决定返回下一级。 */
    private List<String> browseGravitino(long sourceId, String metalake, String catalogName,
                                         String schema, SourceExtraConfig extra) {
        String lake = SourceExtraConfig.firstNonBlank(metalake, extra.metalake());
        String cat = SourceExtraConfig.firstNonBlank(catalogName, extra.catalog());

        if (lake == null) {
            return gravitino.getSupportedMateLakes(sourceId);
        }
        if (cat == null) {
            return gravitino.getSupportedCatalogs(sourceId, lake);
        }
        if (schema == null || schema.isBlank()) {
            return gravitino.getSupportedSchemas(sourceId, lake, cat);
        }
        return gravitino.getSupportedTables(sourceId, lake, cat, schema);
    }

    /**
     * 列出 dbx 某个库下的 schema。
     *
     * <p><b>MySQL 这类数据库没有 schema 这一层</b>：库本身就是 schema，
     * dbx 的 {@code /api/schema/schemas} 对它们返回空数组，而
     * {@code /api/schema/tables} 要求把库名当 schema 传进去。
     * 直接把空数组透出去的话，页面上的 schema 下拉是空的，级联就断在这儿了。
     * 这里退化成「库本身」，让三级级联对两类数据库都走得通。
     */
    private static List<String> dbxSchemas(DbxClient client, String connectionId, String database) {
        List<String> schemas = client.schemas(connectionId, database);
        return schemas.isEmpty() ? List.of(database) : schemas;
    }

    /** database → schema → table。 */
    private List<String> browseDbx(MetadataSourceRow source, SourceExtraConfig extra,
                                   String database, String schema, String override) {
        String connectionId = requireConnectionId(source, extra, override);
        DbxClient client = dbxClient(source);
        ensureDbxConnection(client, source, connectionId);

        String db = SourceExtraConfig.firstNonBlank(database, extra.database());
        if (db == null) {
            return client.databases(connectionId);
        }
        if (schema == null || schema.isBlank()) {
            return dbxSchemas(client, connectionId, db);
        }
        return client.tables(connectionId, db, schema);
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    @Override
    public long submitSync(LineageContext ctx, MetaSyncRequest request) {
        MetadataSourceRow source = requireSource(ctx, request.sourceId());
        if (source.type() == MetadataSourceType.CATALOG) {
            throw new IllegalArgumentException(
                    "「" + source.name() + "」就是本地目录本身，不能从它同步");
        }
        SyncScope scope = request.scopeOrDefault();
        if (scope == SyncScope.TABLE && request.tablesOrEmpty().isEmpty()) {
            throw new IllegalArgumentException("按表导入时请至少选择一张表");
        }

        SourceExtraConfig extra = SourceExtraConfig.parse(source.extraConfig());
        SyncJobRow job = new SyncJobRow(0, ctx.tenantId(), ctx.projectId(), source.id(), scope,
                request.catalog(), request.database(), request.schema(),
                // 连接 id 优先用本次选的，其次回落到来源配置里的默认值
                SourceExtraConfig.firstNonBlank(request.connectionId(), extra.connectionId()),
                request.targetCatalog(), request.tablesOrEmpty(), request.overwriteManualOrDefault(),
                SyncStatus.PENDING, 0, 0, 0, 0, 0, 0, null, List.of(),
                null, null, null, null);

        long jobId = jobs.create(ctx, job);
        logger.info("提交元数据导入任务, {}, jobId={}, 来源={}, 范围={}", ctx, jobId, source.name(), scope);
        // 立刻返回，导入在后台跑 —— 按数据目录导入可能是上万张表
        runner.submit(ctx, jobId, () -> executeJob(ctx, jobId));
        return jobId;
    }

    @Override
    public SyncJobResponse getJob(LineageContext ctx, long jobId) {
        return jobs.findById(ctx, jobId).map(SyncJobResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("导入任务不存在: " + jobId));
    }

    @Override
    public List<SyncJobResponse> listJobs(LineageContext ctx, int limit) {
        return jobs.listRecent(ctx, Math.min(Math.max(limit, 1), 50))
                .stream().map(SyncJobResponse::from).toList();
    }

    @Override
    public List<DbxConnectionSummary> listDbxConnections(LineageContext ctx, long sourceId) {
        MetadataSourceRow source = requireSource(ctx, sourceId);
        if (source.type() != MetadataSourceType.DBX) {
            throw new IllegalArgumentException(
                    "「" + source.name() + "」不是 dbx 类型，没有连接列表");
        }
        // 返回的是脱敏摘要：dbx 的原始接口会明文返回数据库密码，绝不能透传出去
        return dbxClient(source).listConnections();
    }

    /**
     * 任务体。跑在 {@link SyncJobRunner} 的线程里，租户上下文已由它透传进来。
     *
     * <p>流程：标记开始 → 展开出要导的表 → 写入总数（进度条这时才有分母）→
     * 逐表导入并更新进度 → 收尾。单表失败不打断整批 —— 外部服务里总有权限不足
     * 或类型不支持的个别表，为了一张表放弃其余几百张不合理。
     */
    void executeJob(LineageContext ctx, long jobId) {
        SyncJobRow job = jobs.findById(ctx, jobId)
                .orElseThrow(() -> new IllegalStateException("任务不存在: " + jobId));
        jobs.markRunning(ctx, jobId);

        MetadataSourceRow source = requireSource(ctx, job.sourceId());
        SourceExtraConfig extra = SourceExtraConfig.parse(source.extraConfig());

        DbxClient dbx = null;
        List<String> targets;
        try {
            if (source.type() == MetadataSourceType.DBX) {
                // dbx 的连接是有状态的，整批只建立一次，不要每张表都 connect
                dbx = dbxClient(source);
                ensureDbxConnection(dbx, source, requireConnectionId(source, extra, job.connectionId()));
            }
            targets = expand(job, source, extra, dbx);
        } catch (Exception e) {
            // 展开阶段就失败 = 一张表都没导入，整体判失败
            jobs.finish(ctx, jobId, SyncStatus.FAILED, readable(e), null);
            return;
        }

        jobs.updateTotal(ctx, jobId, targets.size());

        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < targets.size(); i++) {
            String table = targets.get(i);
            try {
                MetaCatalogRepository.UpsertResult result = switch (source.type()) {
                    case GRAVITINO -> syncGravitinoTable(ctx, source, extra, job, table);
                    case DBX -> syncDbxTable(ctx, source, extra, job, table, dbx);
                    case CATALOG -> throw new IllegalArgumentException("不能从本地目录同步");
                };
                if (result.skipped()) {
                    skipped++;
                } else if (result.created()) {
                    created++;
                } else {
                    updated++;
                }
            } catch (Exception e) {
                logger.warn("同步表 {} 失败: {}", table, e.getMessage());
                failures.add(table + ": " + readable(e));
            }
            // 每张表都写一次进度：导入很慢，页面需要看到它在动
            jobs.updateProgress(ctx, jobId, i + 1, created, updated, skipped, failures.size());
        }

        SyncStatus status = failures.isEmpty() ? SyncStatus.SUCCESS
                : (created + updated + skipped > 0 ? SyncStatus.PARTIAL : SyncStatus.FAILED);
        jobs.finish(ctx, jobId, status,
                failures.isEmpty() ? null : failures.size() + " 张表导入失败", failures);
        logger.info("导入任务结束, {}, jobId={}, 状态={}, 新增={}, 更新={}, 跳过={}, 失败={}",
                ctx, jobId, status, created, updated, skipped, failures.size());
    }

    /**
     * 把导入范围展开成一批具体的表名。
     *
     * <p>放在任务线程里做而不是提交时做：整个数据目录可能有几百个库，
     * 每个库都要打一次外部 API，这一步本身就可能要几十秒。
     */
    private List<String> expand(SyncJobRow job, MetadataSourceRow source,
                                SourceExtraConfig extra, DbxClient dbx) {
        return switch (job.scope()) {
            case TABLE -> job.tables();
            case SCHEMA -> listTables(job, source, extra, dbx, job.sourceSchema());
            case CATALOG -> {
                List<String> all = new ArrayList<>();
                for (String schema : listSchemas(job, source, extra, dbx)) {
                    for (String table : listTables(job, source, extra, dbx, schema)) {
                        // 带上库名，后面逐表导入时要按它定位
                        all.add(schema + "." + table);
                    }
                }
                yield all;
            }
        };
    }

    private List<String> listSchemas(SyncJobRow job, MetadataSourceRow source,
                                     SourceExtraConfig extra, DbxClient dbx) {
        if (source.type() == MetadataSourceType.GRAVITINO) {
            String lake = require(SourceExtraConfig.firstNonBlank(null, extra.metalake()),
                    "Gravitino 导入需要指定 metalake");
            return gravitino.getSupportedSchemas(source.id(), lake,
                    require(job.sourceCatalog(), "Gravitino 导入需要指定 catalog"));
        }
        // 同样要处理「没有 schema 层」的数据库，否则按库导入会展开出 0 张表
        return dbxSchemas(dbx, requireConnectionId(source, extra, job.connectionId()),
                require(job.sourceDatabase(), "dbx 导入需要指定数据库"));
    }

    private List<String> listTables(SyncJobRow job, MetadataSourceRow source,
                                    SourceExtraConfig extra, DbxClient dbx, String schema) {
        if (source.type() == MetadataSourceType.GRAVITINO) {
            String lake = SourceExtraConfig.firstNonBlank(null, extra.metalake());
            return gravitino.getSupportedTables(source.id(), lake, job.sourceCatalog(), schema);
        }
        return dbx.tables(requireConnectionId(source, extra, job.connectionId()),
                job.sourceDatabase(), schema);
    }

    /**
     * 目标数据目录：本次导入指定的优先，否则沿用源端，并顺手登记进数据目录配置。
     *
     * <p>登记是为了让配置页看得到它 —— 否则会出现「元数据管理里筛得出这个目录、
     * 配置页里却没有」，用户也就没法给它配临时表规则。
     */
    private String targetCatalog(LineageContext ctx, SyncJobRow job, String sourceCatalog) {
        return dataCatalogService.ensureCatalog(ctx,
                SourceExtraConfig.firstNonBlank(job.targetCatalog(), sourceCatalog));
    }

    /** {@code CATALOG} 范围下表名带着库名前缀，这里拆开。 */
    private static String schemaOf(SyncJobRow job, String table) {
        int dot = table.indexOf('.');
        return dot > 0 ? table.substring(0, dot) : job.sourceSchema();
    }

    private static String simpleName(String table) {
        int dot = table.indexOf('.');
        return dot > 0 ? table.substring(dot + 1) : table;
    }

    private MetaCatalogRepository.UpsertResult syncGravitinoTable(
            LineageContext ctx, MetadataSourceRow source, SourceExtraConfig extra,
            SyncJobRow job, String qualified) {

        String lake = require(SourceExtraConfig.firstNonBlank(null, extra.metalake()),
                "Gravitino 同步需要指定 metalake");
        String cat = require(SourceExtraConfig.firstNonBlank(job.sourceCatalog(), extra.catalog()),
                "Gravitino 同步需要指定 catalog");
        // CATALOG 范围下表名带着库名前缀，这里拆回来
        String schema = require(SourceExtraConfig.firstNonBlank(schemaOf(job, qualified), extra.schema()),
                "Gravitino 同步需要指定 schema");
        String table = simpleName(qualified);

        GravitinoTableSchema loaded = gravitino.getTableSchema(source.id(), lake, cat, schema, table);

        // 目标数据目录：本次导入指定的优先，否则沿用源端的 catalog。
        // 库名与表名一律跟随源端 —— 改了之后 SQL 解析按 schema.table 就对不上
        return catalog.upsert(ctx,
                metaTable(targetCatalog(ctx, job, cat), schema, table, loaded.comment(),
                        source, MetaSource.GRAVITINO),
                gravitinoColumns(loaded), job.overwriteManual());
    }

    /**
     * Gravitino 的字段 → 本地行。
     *
     * <p>抽出来是为了让只读预览（{@link #loadRemoteTable}）与导入走<b>同一份</b>映射：
     * 两处各写一遍的话，页面上看到的字段和真正导进来的会慢慢对不上。
     *
     * <p>主键一律 false：Gravitino 的列模型里没有这个概念，编一个出来不如留空。
     */
    private static List<MetaColumnRow> gravitinoColumns(GravitinoTableSchema loaded) {
        List<MetaColumnRow> columns = new ArrayList<>();
        int ordinal = 1;
        for (GravitinoColumn c : loaded.columns()) {
            columns.add(MetaColumnRow.of(c.name(), c.dataType(), c.comment(),
                    ordinal++, c.partition(), c.nullable(), false, MetaSource.GRAVITINO));
        }
        return columns;
    }

    private MetaCatalogRepository.UpsertResult syncDbxTable(
            LineageContext ctx, MetadataSourceRow source, SourceExtraConfig extra,
            SyncJobRow job, String qualified, DbxClient dbx) {

        String connectionId = requireConnectionId(source, extra, job.connectionId());
        String database = SourceExtraConfig.firstNonBlank(job.sourceDatabase(), extra.database());
        String schema = require(SourceExtraConfig.firstNonBlank(schemaOf(job, qualified), extra.schema()),
                "dbx 同步需要指定 schema");
        String table = simpleName(qualified);

        List<DbxColumn> loaded = dbx.columnDetails(connectionId, database, schema, table);
        if (loaded.isEmpty()) {
            throw new IllegalStateException("未取到任何字段，检查表名与连接是否正确");
        }

        // 表注释在 /api/schema/tables 里（不在 columns 接口里），单独取一次。
        // 原先这里写死 null，导致从 dbx 导进来的表中文名全是空的。
        // database 是 dbx 里 schema 之上的一层，语义上对应数据目录
        return catalog.upsert(ctx,
                metaTable(targetCatalog(ctx, job, database), schema, table,
                        dbx.tableComment(connectionId, database, schema, table),
                        source, MetaSource.DBX),
                dbxColumns(loaded), job.overwriteManual());
    }

    /**
     * dbx 的字段 → 本地行。理由同 {@link #gravitinoColumns}：预览与导入共用一份映射。
     *
     * <p>分区标记一律 false：dbx 的 schema 接口不返回分区信息。
     * <b>这里绝对不能推断</b> —— 血缘解析靠分区列做判断，编错了整条链路的结果都会偏。
     */
    private static List<MetaColumnRow> dbxColumns(List<DbxColumn> loaded) {
        List<MetaColumnRow> columns = new ArrayList<>();
        int ordinal = 1;
        for (DbxColumn c : loaded) {
            columns.add(MetaColumnRow.of(c.name(), c.dataType(), c.comment(),
                    ordinal++, false, c.nullable(), c.primaryKey(), MetaSource.DBX));
        }
        return columns;
    }

    // ------------------------------------------------------------------

    private static MetaTableRow metaTable(String catalogName, String schema, String table,
                                          String comment, MetadataSourceRow source,
                                          MetaSource metaSource) {
        return MetaTableRow.of(catalogName, schema, table,
                MetaNames.tableFullName(catalogName, schema, table),
                // 表类型（全量表/增量表…）是业务属性，外部服务给不了，留空由用户在页面上选
                null, comment, null, null, metaSource, source.id());
    }

    private MetadataSourceRow requireSource(LineageContext ctx, long sourceId) {
        return sourceService.findRow(ctx, sourceId)
                .orElseThrow(() -> new IllegalArgumentException("元数据服务配置不存在: " + sourceId));
    }

    /**
     * 建立 dbx 连接，连接 id 不存在时给出可操作的报错。
     *
     * <p>不这么做的话，后续 schema 调用只会回一句 {@code Connection config not found}，
     * 用户看不出该去哪儿改，也不知道有哪些连接可选。
     */
    private static void ensureDbxConnection(DbxClient client, MetadataSourceRow source,
                                            String connectionId) {
        if (client.connectIfSaved(connectionId)) {
            return;
        }
        // 没在已保存列表里，也可能是「只在 dbx 界面上连过、没保存」的连接，
        // 那种连接跨会话用不了，这里直接说清楚
        throw new IllegalArgumentException(
                "dbx 中找不到已保存的连接「" + connectionId + "」。"
                        + "请在元数据服务「" + source.name() + "」的 extraConfig 里修正 connectionId。"
                        + "当前 dbx 中已保存的连接: " + client.savedConnectionIds());
    }

    private DbxClient dbxClient(MetadataSourceRow source) {
        return dbxClients.create(source.baseUrl(), sourceService.decryptCredential(source));
    }

    /**
     * 取 dbx 连接 id：本次导入选的优先，其次是来源配置里的默认值。
     *
     * <p>页面上已经做成下拉（{@code /api/meta/sync/dbx-connections}），
     * 所以报错信息也一并改掉 —— 让用户去手写 extraConfig 是上一版的做法。
     */
    private static String requireConnectionId(MetadataSourceRow source, SourceExtraConfig extra,
                                              String override) {
        return require(SourceExtraConfig.firstNonBlank(override, extra.connectionId()),
                "dbx 来源「" + source.name() + "」还没有选择连接。"
                        + "请在导入弹窗里选一个 dbx 中已保存的连接");
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String readable(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }
}
