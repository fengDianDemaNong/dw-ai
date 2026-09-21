package com.dwai.lineage.service.impl;

import com.dwai.lineage.dto.DataCatalogRequest;
import com.dwai.lineage.dto.DataCatalogResponse;
import com.dwai.lineage.dto.TempRuleRequest;
import com.dwai.lineage.dto.TempRuleResponse;
import com.dwai.lineage.dto.TempRuleTestResponse;
import com.dwai.lineage.enums.MatchType;
import com.dwai.lineage.enums.TempRuleTarget;
import com.dwai.lineage.exception.ResourceInUseException;
import com.dwai.lineage.persistence.DataCatalogRepository;
import com.dwai.lineage.persistence.DataCatalogRow;
import com.dwai.lineage.persistence.TempRuleRow;
import com.dwai.lineage.service.DataCatalogService;
import com.dwai.lineage.service.metadata.CatalogPolicy;
import com.dwai.lineage.service.metadata.RegexGuard;
import com.dwai.lineage.service.metadata.TempTableMatcher;
import com.dwai.lineage.tenant.LineageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class DataCatalogServiceImpl implements DataCatalogService {

    private static final Logger logger = LoggerFactory.getLogger(DataCatalogServiceImpl.class);

    /** 系统兜底的默认目录名，与建库脚本里种下的那条一致。 */
    public static final String FALLBACK_CATALOG = "default";

    private final DataCatalogRepository repository;

    public DataCatalogServiceImpl(DataCatalogRepository repository) {
        this.repository = repository;
    }

    // ==================== 数据目录 ====================

    @Override
    public List<DataCatalogResponse> listCatalogs(LineageContext ctx) {
        ensureDefaultExists(ctx);
        return repository.listCatalogs(ctx).stream().map(row -> toResponse(ctx, row)).toList();
    }

    @Override
    public String defaultCatalogName(LineageContext ctx) {
        return ensureDefaultExists(ctx).name();
    }

    @Override
    @Transactional
    public DataCatalogResponse createCatalog(LineageContext ctx, DataCatalogRequest request) {
        String name = normalizeName(request.name());
        if (repository.findCatalogByName(ctx, name).isPresent()) {
            throw new IllegalArgumentException("数据目录已存在: " + name);
        }
        // 第一个目录必须是默认的 —— 否则项目会处于「有目录但没默认目录」的状态
        boolean first = repository.listCatalogs(ctx).isEmpty();
        long id = repository.createCatalog(ctx,
                DataCatalogRow.of(name, first, request.description()));
        return toResponse(ctx, requireCatalog(ctx, id));
    }

    @Override
    @Transactional
    public DataCatalogResponse updateCatalog(LineageContext ctx, long id, DataCatalogRequest request) {
        DataCatalogRow existing = requireCatalog(ctx, id);
        String name = normalizeName(request.name());

        if (!name.equals(existing.name())) {
            // 改名会让该目录下所有表的全名对不上，而全名是血缘与元数据关联的键。
            // 与其做一次跨三张表的批量重写，不如让用户新建一个目录重新导入 —— 可控得多。
            int tables = repository.countTables(ctx, existing.name());
            if (tables > 0) {
                throw new ResourceInUseException(
                        "数据目录「" + existing.name() + "」下已有 " + tables
                                + " 张表，改名会让这些表的全名失效。请新建目录后重新导入。");
            }
            if (repository.findCatalogByName(ctx, name).isPresent()) {
                throw new IllegalArgumentException("数据目录已存在: " + name);
            }
        }
        repository.updateCatalog(ctx, id, name, request.description());
        return toResponse(ctx, requireCatalog(ctx, id));
    }

    @Override
    @Transactional
    public DataCatalogResponse setDefault(LineageContext ctx, long id) {
        requireCatalog(ctx, id);
        repository.setDefaultCatalog(ctx, id);
        return toResponse(ctx, requireCatalog(ctx, id));
    }

    @Override
    @Transactional
    public void deleteCatalog(LineageContext ctx, long id) {
        DataCatalogRow row = requireCatalog(ctx, id);
        if (row.isDefault()) {
            throw new ResourceInUseException(
                    "「" + row.name() + "」是默认数据目录，不能删除。"
                            + "请先把另一个目录设为默认，再删除这一个。");
        }
        int tables = repository.countTables(ctx, row.name());
        if (tables > 0) {
            throw new ResourceInUseException(
                    "数据目录「" + row.name() + "」下还有 " + tables + " 张表，不能删除。"
                            + "请先在元数据管理中删除这些表。");
        }
        repository.deleteCatalog(ctx, id);
    }

    @Override
    public String resolveCatalogName(LineageContext ctx, String requested) {
        if (requested == null || requested.isBlank()) {
            return defaultCatalogName(ctx);
        }
        String name = normalizeName(requested);
        return repository.findCatalogByName(ctx, name)
                .map(DataCatalogRow::name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "数据目录不存在: " + name + "。请先在「数据目录」配置页中创建。"));
    }

    @Override
    @Transactional
    public String ensureCatalog(LineageContext ctx, String name) {
        if (name == null || name.isBlank()) {
            return defaultCatalogName(ctx);
        }
        String normalized = normalizeName(name);
        Optional<DataCatalogRow> existing = repository.findCatalogByName(ctx, normalized);
        if (existing.isPresent()) {
            return existing.get().name();
        }
        logger.info("{} 同步中遇到未登记的数据目录「{}」，自动登记", ctx, normalized);
        repository.createCatalog(ctx,
                DataCatalogRow.of(normalized, false, "同步元数据时自动登记"));
        return normalized;
    }

    /**
     * 保证默认目录存在，没有就建一个。
     *
     * <p>库里理应总有一条（建库脚本种了、升级脚本补了），这里是兜底：
     * 手工删库改数据、或是从更早的版本升上来时可能缺，
     * 而缺了会让所有导入路径都拿不到目录名。
     */
    private DataCatalogRow ensureDefaultExists(LineageContext ctx) {
        Optional<DataCatalogRow> current = repository.findDefaultCatalog(ctx);
        if (current.isPresent()) {
            return current.get();
        }
        // 有目录但都没标默认：把第一个提上来，而不是凭空再造一个
        List<DataCatalogRow> all = repository.listCatalogs(ctx);
        if (!all.isEmpty()) {
            DataCatalogRow first = all.get(0);
            logger.warn("{} 没有默认数据目录，自动把「{}」设为默认", ctx, first.name());
            repository.setDefaultCatalog(ctx, first.id());
            return requireCatalog(ctx, first.id());
        }
        logger.warn("{} 没有任何数据目录，自动创建默认目录「{}」", ctx, FALLBACK_CATALOG);
        long id = repository.createCatalog(ctx,
                DataCatalogRow.of(FALLBACK_CATALOG, true, "系统自动创建的默认数据目录"));
        return requireCatalog(ctx, id);
    }

    private DataCatalogRow requireCatalog(LineageContext ctx, long id) {
        return repository.findCatalog(ctx, id)
                .orElseThrow(() -> new IllegalArgumentException("数据目录不存在: " + id));
    }

    private DataCatalogResponse toResponse(LineageContext ctx, DataCatalogRow row) {
        return new DataCatalogResponse(row.id(), row.name(), row.isDefault(), row.description(),
                repository.countTables(ctx, row.name()));
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("数据目录名称不能为空");
        }
        // 全名比较一律小写，名称也统一存小写，避免 Hive / HIVE 被当成两个目录
        return name.trim().toLowerCase(Locale.ROOT);
    }

    // ==================== 临时库表规则 ====================

    @Override
    public List<TempRuleResponse> listRules(LineageContext ctx) {
        return repository.listRules(ctx).stream().map(DataCatalogServiceImpl::toResponse).toList();
    }

    @Override
    public TempRuleResponse createRule(LineageContext ctx, TempRuleRequest request) {
        TempRuleRow row = toRow(ctx, request);
        long id = repository.createRule(ctx, row);
        return toResponse(repository.findRule(ctx, id).orElseThrow());
    }

    @Override
    public TempRuleResponse updateRule(LineageContext ctx, long id, TempRuleRequest request) {
        requireRule(ctx, id);
        repository.updateRule(ctx, id, toRow(ctx, request));
        return toResponse(repository.findRule(ctx, id).orElseThrow());
    }

    @Override
    public void deleteRule(LineageContext ctx, long id) {
        requireRule(ctx, id);
        repository.deleteRule(ctx, id);
    }

    @Override
    public TempRuleTestResponse testRule(LineageContext ctx, String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("请输入要试算的库名或表名");
        }
        String value = input.trim();
        Optional<TempTableMatcher.Match> match = matcher(ctx).match(value);
        if (match.isEmpty()) {
            return new TempRuleTestResponse(value, false, null, null,
                    "不是临时表，会正常出现在血缘图里并存入库中");
        }
        TempTableMatcher.Match m = match.get();
        String what = m.target() == TempRuleTarget.SCHEMA ? "库名" : "表名";
        String how = m.matchType() == MatchType.GLOB ? "通配符" : "正则";
        return new TempRuleTestResponse(value, true, m.ruleId(), m.pattern(),
                "判为临时表：命中规则 #" + m.ruleId() + "（" + what + " " + how + " " + m.pattern()
                        + "）。血缘图会把它穿透掉，也不会存进库里。");
    }

    @Override
    public TempTableMatcher matcher(LineageContext ctx) {
        return TempTableMatcher.of(repository.listEnabledRules(ctx));
    }

    @Override
    public CatalogPolicy policy(LineageContext ctx) {
        return new CatalogPolicy(defaultCatalogName(ctx), matcher(ctx));
    }

    private TempRuleRow requireRule(LineageContext ctx, long id) {
        return repository.findRule(ctx, id)
                .orElseThrow(() -> new IllegalArgumentException("临时表规则不存在: " + id));
    }

    private TempRuleRow toRow(LineageContext ctx, TempRuleRequest request) {
        TempRuleTarget target = TempRuleTarget.fromString(request.target());
        MatchType matchType = MatchType.fromString(request.matchType());
        String pattern = request.pattern() == null ? "" : request.pattern().trim();
        validatePattern(matchType, pattern);

        String catalog = request.catalogName() == null || request.catalogName().isBlank()
                ? null
                // 作用目录必须真实存在，否则规则永远匹配不上而用户看不出为什么
                : resolveCatalogName(ctx, request.catalogName());

        return new TempRuleRow(0, ctx.tenantId(), ctx.projectId(), catalog, target, matchType,
                pattern, request.enabledOrDefault(), request.description(), null, null);
    }

    /**
     * 写入前就把正则编译一遍。
     *
     * <p>{@link TempTableMatcher} 在读取时会<b>静默跳过</b>编译失败的规则 ——
     * 那是为了不让一条配错的规则把整个血缘功能拖挂。代价是错误无声无息，
     * 所以必须在写入这一侧把关：这里是唯一能把「正则写错了」当场告诉用户的地方。
     */
    private static void validatePattern(MatchType matchType, String pattern) {
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("匹配表达式不能为空");
        }
        if (matchType != MatchType.REGEX) {
            return;
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "正则表达式有语法错误: " + pattern + " —— " + e.getDescription()
                            + "。若只是想用通配符匹配，请把匹配方式改成「通配符」。");
        }
        // 语法对不代表能用：嵌套量词（如 (a+)+）会灾难性回溯，而这条规则将来要对
        // 每张表名跑一遍，卡住一次就是整次血缘解析卡住。运行时有 RegexGuard 兜底
        // 判它不命中，但那是静默的 —— 这里当场拦下，用户才知道自己写了什么
        if (RegexGuard.isCatastrophic(compiled)) {
            throw new IllegalArgumentException(
                    "这个正则在回溯上开销过大，可能让血缘解析卡住: " + pattern
                            + "。常见原因是嵌套量词（如 (a+)+、(.*)* ）。"
                            + "匹配表名通常用「通配符」就够了，比如 tmp_*。");
        }
    }

    private static TempRuleResponse toResponse(TempRuleRow row) {
        return new TempRuleResponse(row.id(), row.catalogName(), row.target().name(),
                row.matchType().name(), row.pattern(), row.enabled(), row.description());
    }
}
