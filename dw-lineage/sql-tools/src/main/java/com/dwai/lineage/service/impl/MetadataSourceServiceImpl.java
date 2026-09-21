package com.dwai.lineage.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.client.GravitinoAdminClient;
import org.apache.gravitino.client.GravitinoMetalake;
import com.dwai.lineage.dto.MetadataSourceRequest;
import com.dwai.lineage.dto.MetadataSourceResponse;
import com.dwai.lineage.dto.MetadataSourceTestRequest;
import com.dwai.lineage.dto.MetadataSourceTestResponse;
import com.dwai.lineage.enums.MetadataSourceType;
import com.dwai.lineage.persistence.MetaCatalogRepository;
import com.dwai.lineage.persistence.MetadataSourceRepository;
import com.dwai.lineage.persistence.MetadataSourceRow;
import com.dwai.lineage.service.MetadataSourceService;
import com.dwai.lineage.service.metadata.CredentialCipher;
import com.dwai.lineage.service.metadata.GravitinoClientRegistry;
import com.dwai.lineage.service.metadata.MetadataServiceFactory;
import com.dwai.lineage.service.metadata.dbx.DbxClient;
import com.dwai.lineage.service.metadata.dbx.DbxClientFactory;
import com.dwai.lineage.service.metadata.provider.CatalogMetadataProvider;
import com.dwai.lineage.service.metadata.provider.CompositeMetadataProvider;
import com.dwai.lineage.service.metadata.provider.DbxMetadataProvider;
import com.dwai.lineage.service.metadata.provider.MetadataProvider;
import com.dwai.lineage.tenant.LineageContext;
import com.dwai.lineage.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 元数据服务配置的实现。
 *
 * <p>凭据的加解密边界收在本类内：写入时加密、读取时永远不放进 DTO、
 * 只有真正要发起外部调用的那一刻才解密成明文并立即用掉。
 */
@Service
public class MetadataSourceServiceImpl implements MetadataSourceService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataSourceServiceImpl.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MetadataSourceRepository repository;
    private final CredentialCipher cipher;
    private final GravitinoClientRegistry gravitinoClients;
    private final MetadataServiceFactory metadataServiceFactory;
    private final MetaCatalogRepository metaCatalogRepository;
    private final DbxClientFactory dbxClients;

    public MetadataSourceServiceImpl(MetadataSourceRepository repository,
                                     CredentialCipher cipher,
                                     GravitinoClientRegistry gravitinoClients,
                                     MetadataServiceFactory metadataServiceFactory,
                                     MetaCatalogRepository metaCatalogRepository,
                                     DbxClientFactory dbxClients) {
        this.repository = repository;
        this.cipher = cipher;
        this.gravitinoClients = gravitinoClients;
        this.metadataServiceFactory = metadataServiceFactory;
        this.metaCatalogRepository = metaCatalogRepository;
        this.dbxClients = dbxClients;
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    @Override
    public List<MetadataSourceResponse> list(LineageContext ctx) {
        return repository.list(ctx).stream().map(MetadataSourceResponse::from).toList();
    }

    @Override
    public MetadataSourceResponse create(LineageContext ctx, MetadataSourceRequest request) {
        MetadataSourceType type = MetadataSourceType.fromString(request.type());
        validateExtraConfig(type, request.extraConfig());

        MetadataSourceRow row = new MetadataSourceRow(
                0, ctx.tenantId(),
                request.name().strip(), type, request.baseUrl().strip(),
                cipher.encrypt(emptyToNull(request.credential())),
                emptyToNull(request.extraConfig()),
                request.priorityOrDefault(), request.enabledOrDefault(), null, null);

        long id = repository.insert(ctx, row);
        logger.info("新增元数据服务配置, {}, id={}, type={}, name={}", ctx, id, type, row.name());
        return repository.findById(ctx, id).map(MetadataSourceResponse::from).orElseThrow();
    }

    @Override
    public MetadataSourceResponse update(LineageContext ctx, long id, MetadataSourceRequest request) {
        MetadataSourceRow existing = requireRow(ctx, id);
        MetadataSourceType type = MetadataSourceType.fromString(request.type());
        validateExtraConfig(type, request.extraConfig());

        // 前端不回传凭据明文，留空即表示「不改」，而不是「清空」
        boolean keepCredential = emptyToNull(request.credential()) == null;

        MetadataSourceRow row = new MetadataSourceRow(
                id, ctx.tenantId(),
                request.name().strip(), type, request.baseUrl().strip(),
                keepCredential ? existing.credential() : cipher.encrypt(request.credential()),
                emptyToNull(request.extraConfig()),
                request.priorityOrDefault(), request.enabledOrDefault(), null, null);

        if (!repository.update(ctx, id, row, keepCredential)) {
            throw new IllegalArgumentException("元数据服务配置不存在: " + id);
        }

        // 地址变了就丢弃按旧地址建的客户端，否则连接池会一直指向老服务
        if (!existing.baseUrl().equals(row.baseUrl())) {
            gravitinoClients.evict(existing.baseUrl());
        }
        logger.info("更新元数据服务配置, {}, id={}", ctx, id);
        return repository.findById(ctx, id).map(MetadataSourceResponse::from).orElseThrow();
    }

    @Override
    public void delete(LineageContext ctx, long id) {
        MetadataSourceRow existing = requireRow(ctx, id);
        if (existing.type().isBuiltIn()) {
            // 删掉它，本地元数据目录就再也参与不了解析链，且用户无法自己加回来。
            // 不想用就停用，不要删。
            throw new IllegalArgumentException(
                    "「" + existing.name() + "」是内置来源，不能删除。如不需要，请将其停用");
        }
        repository.delete(ctx, id);
        gravitinoClients.evict(existing.baseUrl());
        logger.info("删除元数据服务配置, {}, id={}", ctx, id);
    }

    // ------------------------------------------------------------------
    // 测试连接
    // ------------------------------------------------------------------

    @Override
    public MetadataSourceTestResponse test(LineageContext ctx, MetadataSourceTestRequest request) {
        try {
            Resolved resolved = resolveForTest(ctx, request);
            return switch (resolved.type()) {
                case GRAVITINO -> MetadataSourceTestResponse.ok(testGravitino(resolved.baseUrl()));
                case DBX -> MetadataSourceTestResponse.ok(
                        dbxClients.create(resolved.baseUrl(), resolved.credential()).testConnection());
                // 本地目录没有「连不连得上」的问题，报一下目前存了多少张表更有用
                case CATALOG -> MetadataSourceTestResponse.ok(
                        "本地目录可用，当前已收录 " + metaCatalogRepository.count(ctx, null, null, null) + " 张表");
            };
        } catch (Exception e) {
            // 连不上是这个接口的正常结果，不往上抛：前端要把原因显示在表单旁边。
            // 只记 message 不记堆栈，避免异常链里可能带上的请求体进日志。
            logger.info("元数据服务连通性测试失败: {}", e.getMessage());
            return MetadataSourceTestResponse.fail(readable(e));
        }
    }

    private String testGravitino(String baseUrl) {
        GravitinoAdminClient client = gravitinoClients.client(baseUrl);
        GravitinoMetalake[] metalakes = client.listMetalakes();
        int count = metalakes == null ? 0 : metalakes.length;
        return "连接成功，发现 " + count + " 个 metalake";
    }

    /** 表单优先、库中兜底：只改地址不重输密码是很常见的操作。 */
    private Resolved resolveForTest(LineageContext ctx, MetadataSourceTestRequest request) {
        MetadataSourceRow existing = request.id() == null
                ? null : repository.findById(ctx, request.id()).orElse(null);

        String typeText = request.type() != null && !request.type().isBlank()
                ? request.type() : (existing == null ? null : existing.type().name());
        if (typeText == null) {
            throw new IllegalArgumentException("请指定要测试的元数据服务类型");
        }

        String baseUrl = request.baseUrl() != null && !request.baseUrl().isBlank()
                ? request.baseUrl().strip() : (existing == null ? null : existing.baseUrl());
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("请填写服务地址");
        }

        String credential = emptyToNull(request.credential());
        if (credential == null && existing != null) {
            credential = decryptCredential(existing);
        }

        MetadataSourceType type = MetadataSourceType.fromString(typeText);
        if (type.requiresCredential() && (credential == null || credential.isBlank())) {
            throw new IllegalArgumentException("该类型需要凭据，请填写后再测试");
        }
        return new Resolved(type, baseUrl, credential);
    }

    private record Resolved(MetadataSourceType type, String baseUrl, String credential) {
    }

    // ------------------------------------------------------------------
    // 供血缘解析使用
    // ------------------------------------------------------------------

    /**
     * 构造外部元数据来源。
     *
     * <p>指定 {@code sourceId} 时只用那一个；不指定则把全部启用的来源按 priority 串起来，
     * 交给已有的 {@link CompositeMetadataProvider} 处理「前一个查不到才问后一个」
     * 与「某个来源整体不可用时降级」。
     *
     * @param metalake Gravitino 专用，覆盖 extraConfig 里的默认值
     * @param catalog  同上
     */
    @Override
    public Optional<MetadataProvider> externalProvider(LineageContext ctx, Long sourceId,
                                                       String metalake, String catalog) {
        if (sourceId != null) {
            MetadataSourceRow row = requireRow(ctx, sourceId);
            if (!row.enabled()) {
                throw new IllegalArgumentException("元数据服务「" + row.name() + "」已停用，请先启用或改选其它来源");
            }
            return Optional.of(buildProvider(row, metalake, catalog));
        }

        List<MetadataProvider> providers = new ArrayList<>();
        for (MetadataSourceRow row : repository.listEnabled(ctx)) {
            try {
                providers.add(buildProvider(row, metalake, catalog));
            } catch (Exception e) {
                // 自动串联时单个来源配置不全不该让整次解析失败，跳过并留痕
                logger.warn("跳过元数据来源「{}」: {}", row.name(), e.getMessage());
            }
        }
        return providers.isEmpty()
                ? Optional.empty()
                : Optional.of(new CompositeMetadataProvider(providers));
    }

    private MetadataProvider buildProvider(MetadataSourceRow row, String metalake, String catalog) {
        return switch (row.type()) {
            case GRAVITINO -> buildGravitino(row, metalake, catalog);
            case DBX -> buildDbx(row);
            // 本地目录不走网络，直接读库；租户上下文取自当前请求
            case CATALOG -> new CatalogMetadataProvider(
                    metaCatalogRepository, TenantContextHolder.require());
        };
    }

    private MetadataProvider buildGravitino(MetadataSourceRow row, String metalake, String catalog) {
        JsonNode extra = parseExtra(row.extraConfig());
        String lake = firstNonBlank(metalake, text(extra, "metalake"));
        String cat = firstNonBlank(catalog, text(extra, "catalog"));
        if (lake == null || cat == null) {
            throw new IllegalArgumentException(
                    "Gravitino 来源「" + row.name() + "」需要 metalake 与 catalog："
                            + "请在解析时选择，或在该配置的 extraConfig 中填写默认值 "
                            + "{\"metalake\":\"...\",\"catalog\":\"...\"}");
        }
        Catalog loaded = gravitinoClients.client(row.baseUrl()).loadMetalake(lake).loadCatalog(cat);
        return metadataServiceFactory.gravitinoProvider(loaded);
    }

    private MetadataProvider buildDbx(MetadataSourceRow row) {
        JsonNode extra = parseExtra(row.extraConfig());
        String connectionId = text(extra, "connectionId");
        if (connectionId == null) {
            throw new IllegalArgumentException(
                    "dbx 来源「" + row.name() + "」需要指定连接：请在 extraConfig 中填写 "
                            + "{\"connectionId\":\"dbx 中已保存的连接 id\",\"database\":\"...\",\"schema\":\"...\"}");
        }
        DbxClient client = dbxClients.create(row.baseUrl(), decryptCredential(row));
        return new DbxMetadataProvider(client, connectionId, text(extra, "database"), text(extra, "schema"));
    }

    @Override
    public Optional<MetadataSourceRow> findRow(LineageContext ctx, long id) {
        return repository.findById(ctx, id);
    }

    @Override
    public String decryptCredential(MetadataSourceRow row) {
        return row.hasCredential() ? cipher.decrypt(row.credential()) : null;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private MetadataSourceRow requireRow(LineageContext ctx, long id) {
        return repository.findById(ctx, id)
                .orElseThrow(() -> new IllegalArgumentException("元数据服务配置不存在: " + id));
    }

    /** 提前校验 JSON，避免存进去一段解析不了的文本，等到解析血缘时才炸。 */
    private static void validateExtraConfig(MetadataSourceType type, String extraConfig) {
        if (extraConfig == null || extraConfig.isBlank()) {
            return;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(extraConfig);
        } catch (Exception e) {
            throw new IllegalArgumentException("extraConfig 不是合法的 JSON: " + e.getMessage());
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("extraConfig 必须是 JSON 对象");
        }
        if (type == MetadataSourceType.DBX && text(node, "connectionId") == null) {
            throw new IllegalArgumentException(
                    "dbx 的 extraConfig 必须包含 connectionId（dbx 中已保存的连接 id）");
        }
    }

    private static JsonNode parseExtra(String extraConfig) {
        if (extraConfig == null || extraConfig.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(extraConfig);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.strip();
        }
        return (b != null && !b.isBlank()) ? b.strip() : null;
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /** 异常消息可能为空（如 NPE），兜一个类型名，别给用户一个 "null"。 */
    private static String readable(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank())
                ? e.getClass().getSimpleName() : message;
    }
}
