package com.dwai.lineage.service;

import com.dwai.lineage.dto.MetadataSourceRequest;
import com.dwai.lineage.dto.MetadataSourceResponse;
import com.dwai.lineage.dto.MetadataSourceTestRequest;
import com.dwai.lineage.dto.MetadataSourceTestResponse;
import com.dwai.lineage.persistence.MetadataSourceRow;
import com.dwai.lineage.service.metadata.provider.MetadataProvider;
import com.dwai.lineage.tenant.LineageContext;

import java.util.List;
import java.util.Optional;

/**
 * 元数据服务配置的增删改查与连通性测试。
 *
 * <p>凭据的加解密只在本层内部发生：入参收明文、出参永远不含明文。
 */
public interface MetadataSourceService {

    List<MetadataSourceResponse> list(LineageContext ctx);

    MetadataSourceResponse create(LineageContext ctx, MetadataSourceRequest request);

    MetadataSourceResponse update(LineageContext ctx, long id, MetadataSourceRequest request);

    void delete(LineageContext ctx, long id);

    /** 测试连接。连不上返回 {@code success=false} 而不是抛异常。 */
    MetadataSourceTestResponse test(LineageContext ctx, MetadataSourceTestRequest request);

    /**
     * 按配置构造外部元数据 provider，供血缘解析使用。
     *
     * @param sourceId 指定来源；为 null 时取全部启用的来源按 priority 串联
     * @param metalake Gravitino 专用，覆盖该配置 extraConfig 里的默认值；其它类型忽略
     * @param catalog  同上
     * @return 没有任何可用来源时返回 empty，调用方据此退回「DDL + 推断」链路
     */
    Optional<MetadataProvider> externalProvider(LineageContext ctx, Long sourceId,
                                                String metalake, String catalog);

    default Optional<MetadataProvider> externalProvider(LineageContext ctx, Long sourceId) {
        return externalProvider(ctx, sourceId, null, null);
    }

    /** 供 Gravitino 专用接口取原始配置（需要 metalake / catalog 两级参数）。 */
    Optional<MetadataSourceRow> findRow(LineageContext ctx, long id);

    /** 解密某条配置的凭据。仅在真正要发起调用时使用。 */
    String decryptCredential(MetadataSourceRow row);
}
