package com.dwai.lineage.service.impl;

import com.dwai.lineage.persistence.LineageCatalogRepository;
import com.dwai.lineage.persistence.MetaColumnRow;
import com.dwai.lineage.persistence.MetaTableRow;
import io.github.melin.superior.common.relational.Statement;
import com.dwai.lineage.dto.MetaColumnResponse;
import com.dwai.lineage.dto.MetaColumnUpdateRequest;
import com.dwai.lineage.dto.MetaDdlImportRequest;
import com.dwai.lineage.dto.MetaSyncResult;
import com.dwai.lineage.dto.MetaTableResponse;
import com.dwai.lineage.dto.MetaTableUpdateRequest;
import com.dwai.lineage.dto.PageResponse;
import com.dwai.lineage.exception.SqlParseException;
import com.dwai.lineage.persistence.MetaCatalogRepository;
import com.dwai.lineage.service.DataCatalogService;
import com.dwai.lineage.service.MetaCatalogService;
import com.dwai.lineage.service.metadata.DdlCatalogExtractor;
import com.dwai.lineage.service.metadata.MetadataServiceFactory;
import com.dwai.lineage.tenant.LineageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 元数据目录服务。 */
@Service
public class MetaCatalogServiceImpl implements MetaCatalogService {

    private static final Logger logger = LoggerFactory.getLogger(MetaCatalogServiceImpl.class);

    private final MetaCatalogRepository repository;
    private final MetadataServiceFactory metadataServiceFactory;
    private final DataCatalogService dataCatalogService;
    /** 手工改描述时写穿到血缘侧的快照，见 updateTable。 */
    private final LineageCatalogRepository lineageCatalog;

    public MetaCatalogServiceImpl(MetaCatalogRepository repository,
                                  MetadataServiceFactory metadataServiceFactory,
                                  DataCatalogService dataCatalogService,
                                  LineageCatalogRepository lineageCatalog) {
        this.repository = repository;
        this.metadataServiceFactory = metadataServiceFactory;
        this.dataCatalogService = dataCatalogService;
        this.lineageCatalog = lineageCatalog;
    }

    @Override
    public List<String> listSchemas(LineageContext ctx) {
        return repository.listSchemas(ctx);
    }

    @Override
    public PageResponse<MetaTableResponse> list(LineageContext ctx, String catalog, String schema,
                                                String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = (safePage - 1) * safeSize;

        List<MetaTableResponse> items = repository.list(ctx, catalog, schema, keyword, offset, safeSize)
                .stream().map(MetaTableResponse::from).toList();
        return PageResponse.of(items, repository.count(ctx, catalog, schema, keyword), safePage, safeSize);
    }

    @Override
    public List<String> listCatalogs(LineageContext ctx) {
        return repository.listCatalogs(ctx);
    }

    @Override
    public MetaTableResponse get(LineageContext ctx, long id) {
        return MetaTableResponse.from(requireTable(ctx, id));
    }

    @Override
    public List<MetaColumnResponse> columns(LineageContext ctx, long tableId) {
        requireTable(ctx, tableId);
        return repository.listColumns(ctx, tableId).stream().map(MetaColumnResponse::from).toList();
    }

    @Override
    public MetaSyncResult importDdl(LineageContext ctx, MetaDdlImportRequest request) {
        // 复用与 SQL 解析同一套方言拆分，各方言的建表语法都能吃
        List<Statement> statements;
        try {
            statements = metadataServiceFactory.statements(request.dbType(), request.ddl());
        } catch (SqlParseException e) {
            throw e;
        } catch (Exception e) {
            // 贴错建表语句是很正常的事。解析器抛的是 ANTLR 的 ParseException，
            // 不拦的话会落到全局兜底变成「服务内部错误，请联系管理员」，
            // 而它的原始消息里恰恰带着出错的行列位置，正是用户需要看到的。
            throw new SqlParseException("建表语句解析失败: " + e.getMessage(), e);
        }

        // 目录名先校验再抽取：目录不存在要在建出一批表之前就报错
        String targetCatalog = dataCatalogService.resolveCatalogName(ctx, request.catalogName());

        List<DdlCatalogExtractor.ExtractedTable> extracted =
                DdlCatalogExtractor.extract(statements, request.dbType(), targetCatalog);

        if (extracted.isEmpty()) {
            throw new IllegalArgumentException("没有从中解析出任何 CREATE TABLE 语句");
        }

        int created = 0;
        int updated = 0;
        List<String> skipped = new ArrayList<>();
        List<MetaSyncResult.TableFailure> failures = new ArrayList<>();

        for (DdlCatalogExtractor.ExtractedTable entry : extracted) {
            String fullName = entry.table().fullName();
            try {
                MetaCatalogRepository.UpsertResult result = repository.upsert(
                        ctx, entry.table(), entry.columns(), request.overwriteManualOrDefault());
                if (result.skipped()) {
                    skipped.add(fullName);
                } else if (result.created()) {
                    created++;
                } else {
                    updated++;
                }
            } catch (Exception e) {
                // 一张表失败不影响其它表，与同步的行为保持一致
                logger.warn("导入建表语句失败, 表={}: {}", fullName, e.getMessage());
                failures.add(new MetaSyncResult.TableFailure(fullName, readable(e)));
            }
        }

        logger.info("建表语句导入完成, {}, 新增={}, 更新={}, 跳过={}, 失败={}",
                ctx, created, updated, skipped.size(), failures.size());
        return new MetaSyncResult(created, updated, skipped.size(), failures.size(), skipped, failures);
    }

    @Override
    @Transactional
    public MetaTableResponse updateTable(LineageContext ctx, long id, MetaTableUpdateRequest request) {
        MetaTableRow before = requireTable(ctx, id);
        repository.updateTableInfo(ctx, id, blankToNull(request.tableType()),
                blankToNull(request.comment()), blankToNull(request.remark()));

        // 写穿到血缘侧：那边存的是快照，正常靠重新解析保存刷新。
        // 但用户刚在这张表上点了保存，页面必须当场看到变化，否则那个按钮像坏的
        lineageCatalog.updateTableDescription(ctx, before.fullName(),
                blankToNull(request.tableType()),
                blankToNull(request.comment()), blankToNull(request.remark()));

        return MetaTableResponse.from(requireTable(ctx, id));
    }

    @Override
    @Transactional
    public MetaColumnResponse updateColumn(LineageContext ctx, long id,
                                           MetaColumnUpdateRequest request) {
        MetaColumnRow before = requireColumn(ctx, id);
        repository.updateColumnInfo(ctx, id, blankToNull(request.dataType()),
                blankToNull(request.comment()), blankToNull(request.remark()));

        // 同 updateTable：写穿到血缘侧的快照
        lineageCatalog.updateColumnDescription(ctx, before.fullName(),
                blankToNull(request.dataType()),
                blankToNull(request.comment()), blankToNull(request.remark()));

        return MetaColumnResponse.from(requireColumn(ctx, id));
    }

    @Override
    public void deleteTable(LineageContext ctx, long id) {
        requireTable(ctx, id);
        repository.deleteTable(ctx, id);
        // 血缘数据不受影响：两者隔离存储，删掉一张下线的表不该抹掉它的历史血缘
        logger.info("删除元数据表, {}, id={}", ctx, id);
    }

    @Override
    public void deleteColumn(LineageContext ctx, long id) {
        requireColumn(ctx, id);
        repository.deleteColumn(ctx, id);
    }

    // ------------------------------------------------------------------

    private com.dwai.lineage.persistence.MetaTableRow requireTable(LineageContext ctx, long id) {
        return repository.findById(ctx, id)
                .orElseThrow(() -> new IllegalArgumentException("元数据表不存在: " + id));
    }

    private com.dwai.lineage.persistence.MetaColumnRow requireColumn(LineageContext ctx, long id) {
        return repository.findColumnById(ctx, id)
                .orElseThrow(() -> new IllegalArgumentException("元数据字段不存在: " + id));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }

    private static String readable(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }
}
