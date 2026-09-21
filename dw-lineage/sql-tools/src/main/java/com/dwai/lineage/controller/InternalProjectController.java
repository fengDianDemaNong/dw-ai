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

    private void assertModule(HttpServletRequest req) {
        if (props.isStandalone()) return;
        String expected = props.getModuleToken();
        if (expected != null && !expected.isBlank()) {
            String given = req.getHeader("X-Module-Token");
            if (!expected.equals(given)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "模块令牌无效");
            }
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
