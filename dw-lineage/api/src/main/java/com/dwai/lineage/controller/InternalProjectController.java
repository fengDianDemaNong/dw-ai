package com.dwai.lineage.controller;

import jakarta.servlet.http.HttpServletRequest;
import com.dwai.lineage.conf.LineageProperties;
import com.dwai.lineage.dto.ProjectResponse;
import com.dwai.lineage.service.TenantAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/internal/v1")
public class InternalProjectController {
    private final LineageProperties props;
    private final TenantAdminService tenants;

    public InternalProjectController(LineageProperties props, TenantAdminService tenants) {
        this.props = props;
        this.tenants = tenants;
    }

    @PutMapping("/projects/{projectId}")
    public ProjectResponse upsert(
            HttpServletRequest req,
            @PathVariable String projectId,
            @RequestBody(required = false) Body body) {
        assertModule(req);
        String tenantCode = firstNonBlank(
                body == null ? null : body.tenantCode,
                body == null ? null : body.tenantId,
                req.getHeader("X-Tenant-Code"),
                req.getHeader("X-Tenant-Id"));
        if (tenantCode == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 tenantCode");
        }
        String name = body == null ? projectId : firstNonBlank(body.name, projectId);
        String code = body == null ? projectId : firstNonBlank(body.code, projectId);
        return tenants.upsertFromOrg(projectId, tenantCode, name, code);
    }

    @DeleteMapping("/projects/{projectId}")
    public void delete(HttpServletRequest req, @PathVariable String projectId) {
        assertModule(req);
        String tenantCode = firstNonBlank(req.getHeader("X-Tenant-Id"));
        tenants.deleteFromOrg(projectId, tenantCode);
    }

    /**
     * 服务间调用的唯一门禁：静态共享密钥（与 dw-org / dw-model 同一形态）。
     *
     * <p><b>未配置密钥时拒绝，而不是放行。</b>此前这里是「密钥为空就跳过校验」，
     * 而 {@code /internal/v1/**} 不在 SecurityConfig 的鉴权范围内 ——
     * 叠加的效果是：默认部署（module-token 为空）下这两个入口（PUT / DELETE 项目镜像）
     * 任何人都能调。现在未配置的表现是 401，错误信息里带要设的属性名。
     *
     * <p>{@link MessageDigest#isEqual} 做定长比对，避免按响应耗时逐字节猜密钥。
     *
     * <p>独立模式放行是刻意的：没有组织平面，不存在服务间调用。
     *
     * <p><b>改动需三处同步</b>：dw-org 的 {@code InternalController.assertInternal}、
     * dw-model 的 {@code WarehouseInternalController.assertModule}。
     */
    private void assertModule(HttpServletRequest req) {
        if (props.isStandalone()) return;
        String expected = props.getModuleToken();
        if (expected == null || expected.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "未配置模块令牌，服务间接口已拒绝：请设置 lineage.module-token（环境变量 MODULE_TOKEN）");
        }
        String given = req.getHeader("X-Module-Token");
        if (given == null
                || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        given.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "模块令牌无效");
        }
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    public static class Body {
        public String name;
        public String code;
        public String tenantId;
        public String tenantCode;
    }
}
