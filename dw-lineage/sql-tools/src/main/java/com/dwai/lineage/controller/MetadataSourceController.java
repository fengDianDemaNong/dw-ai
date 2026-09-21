package com.dwai.lineage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.dwai.lineage.dto.MetadataSourceRequest;
import com.dwai.lineage.dto.MetadataSourceResponse;
import com.dwai.lineage.dto.MetadataSourceTestRequest;
import com.dwai.lineage.dto.MetadataSourceTestResponse;
import com.dwai.lineage.service.MetadataSourceService;
import com.dwai.lineage.service.metadata.CredentialCipher;
import com.dwai.lineage.tenant.TenantContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 元数据服务配置。
 *
 * <p>租户上下文由 {@code TenantInterceptor} 从请求头解析，这里直接取。
 *
 * <p><b>响应中不会出现任何凭据明文</b>，只用 {@code credentialConfigured} 表示是否已配置。
 */
@Tag(name = "元数据服务配置")
@RestController
@RequestMapping("/api/metadata-sources")
public class MetadataSourceController {

    private final MetadataSourceService service;
    private final CredentialCipher cipher;

    public MetadataSourceController(MetadataSourceService service, CredentialCipher cipher) {
        this.service = service;
        this.cipher = cipher;
    }

    @Operation(
            summary = "列出元数据服务配置",
            description = """
                    当前租户下的 Gravitino / dbx 连接。响应不含凭据明文，只有 credentialConfigured。
                    解析或同步时把返回的 id 当作 sourceId。
                    这是租户级配置，同一租户下各项目共用。
                    """)
    @GetMapping
    public List<MetadataSourceResponse> list() {
        return service.list(TenantContextHolder.require());
    }

    /**
     * 运行环境能力。
     *
     * <p>页面据此决定要不要提示「未配置 METADATA_SECRET_KEY，保存带凭据的服务会失败」——
     * 比让用户填完表单点保存才报错要友好。
     */
    @Operation(
            summary = "查询是否已配置凭据加密",
            description = "返回 credentialEncryptionAvailable。为 false 时保存带凭据的服务会失败，应先配 METADATA_SECRET_KEY。")
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of("credentialEncryptionAvailable", cipher.isConfigured());
    }

    @Operation(
            summary = "新建元数据服务",
            description = """
                    type 为 GRAVITINO 或 DBX。凭据会加密存储，响应不再回传明文。
                    保存前可用 POST /api/metadata-sources/test 先试连通。
                    """)
    @PostMapping
    public MetadataSourceResponse create(@Valid @RequestBody MetadataSourceRequest request) {
        return service.create(TenantContextHolder.require(), request);
    }

    @Operation(
            summary = "更新元数据服务",
            description = "按 id 更新。credential 留空表示不改库中原值。")
    @PutMapping("/{id}")
    public MetadataSourceResponse update(
            @Parameter(description = "元数据服务 id", required = true)
            @PathVariable long id,
            @Valid @RequestBody MetadataSourceRequest request) {
        return service.update(TenantContextHolder.require(), id, request);
    }

    @Operation(
            summary = "删除元数据服务",
            description = "删除配置本身。已导入的本地元数据表不会级联删除。")
    @DeleteMapping("/{id}")
    public void delete(
            @Parameter(description = "元数据服务 id", required = true)
            @PathVariable long id) {
        service.delete(TenantContextHolder.require(), id);
    }

    /**
     * 测试连接。
     *
     * <p>连不上返回 {@code 200 + success=false}，不是 4xx/5xx —— 连不上是这个接口的
     * 正常结果之一，前端需要把原因显示在表单旁边而不是弹全局错误。
     */
    @Operation(
            summary = "测试元数据服务连通性",
            description = """
                    两种用法：只传 id 测已保存配置；或传 type+baseUrl+credential 测尚未保存的表单。
                    连不上返回 HTTP 200 且 success=false（不是 4xx），请读 message，不要当系统错误。
                    """)
    @PostMapping("/test")
    public MetadataSourceTestResponse test(@RequestBody MetadataSourceTestRequest request) {
        return service.test(TenantContextHolder.require(), request);
    }
}
