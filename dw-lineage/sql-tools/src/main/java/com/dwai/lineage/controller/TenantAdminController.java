package com.dwai.lineage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.dwai.lineage.dto.ActiveContextResponse;
import com.dwai.lineage.dto.ProjectRequest;
import com.dwai.lineage.dto.ProjectResponse;
import com.dwai.lineage.dto.TenantRequest;
import com.dwai.lineage.dto.TenantResponse;
import com.dwai.lineage.conf.LineageProperties;
import com.dwai.lineage.service.TenantAdminService;
import com.dwai.lineage.tenant.TenantContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 租户与项目的管理面。
 *
 * <p>与其它 controller 不同，这里的接口<b>不取</b> {@code TenantContextHolder} ——
 * 它们是跨租户的管理操作，作用对象由路径参数指定，而不是由请求头指定。
 * 唯一的例外是 {@link #context()}，它的职责恰恰就是回报请求头解析出了什么。
 *
 * <p>当前没有登录体系，因此这些接口<b>不做权限校验</b>，任何能访问服务的人都能建租户。
 * 定位是内部工具，租户是数据组织维度而非安全边界；接入登录体系后需要在这里加管理员校验。
 */
@Tag(name = "租户与项目管理")
@RestController
@RequestMapping("/api")
public class TenantAdminController {

    private final TenantAdminService service;
    private final LineageProperties props;

    public TenantAdminController(TenantAdminService service, LineageProperties props) {
        this.service = service;
        this.props = props;
    }

    /**
     * 当前请求实际生效的租户与项目。
     *
     * <p>前端启动时调一次：确认自己发的头被采纳了，并拿到名称显示在切换器上。
     * 若 {@code tenantExists=false}，说明本地存的是个已被删除的租户，前端应回落到默认租户。
     */
    @Operation(
            summary = "查询当前生效的租户与项目",
            description = """
                    根据请求头 X-Tenant-Id / X-Project-Id（未传则用默认值）回报实际生效的租户、项目名称及是否仍存在。
                    适用：启动时核对上下文、切换器展示、发现本地缓存的租户已被删除（tenantExists=false 时应回落默认租户）。
                    不要用本接口做租户/项目列表；列表请分别调「列出全部租户」「列出某租户下的项目」。
                    """)
    @GetMapping("/context")
    public ActiveContextResponse context() {
        return service.describe(TenantContextHolder.require());
    }

    @Operation(
            summary = "列出全部租户",
            description = "跨租户管理面，不读请求头。返回所有租户（含停用）。要在某个租户下做事，先从这里拿到 id，再调项目接口或在后续业务请求里带 X-Tenant-Id。")
    @GetMapping("/tenants")
    public List<TenantResponse> listTenants() {
        return service.listTenants();
    }

    @Operation(
            summary = "新建租户",
            description = """
                    创建一个租户。code 是稳定标识，创建后不可通过更新接口改掉。
                    status 新建时忽略，一律为启用。
                    当前无登录鉴权。创建后通常还要再调「在指定租户下新建项目」，业务数据按项目隔离。
                    """)
    @PostMapping("/tenants")
    public TenantResponse createTenant(@Valid @RequestBody TenantRequest request) {
        assertLocalAdmin();
        return service.createTenant(request);
    }

    @Operation(
            summary = "更新租户名称或状态",
            description = """
                    按路径中的租户 id 更新。请求体里的 code 会被忽略（编码创建后不可改）。
                    status：1 启用，0 停用。停用不会删除其下数据。
                    若要把租户删掉，用删除接口；有业务数据时删除会 409，应改停用。
                    """)
    @PutMapping("/tenants/{id}")
    public TenantResponse updateTenant(
            @Parameter(description = "租户数字 id，来自列出租户或新建租户的返回值", required = true)
            @PathVariable long id,
            @Valid @RequestBody TenantRequest request) {
        assertLocalAdmin();
        return service.updateTenant(id, request);
    }

    /** 其下还有业务数据时返回 409，提示改用停用。 */
    @Operation(
            summary = "删除租户",
            description = """
                    物理删除租户。其下仍有项目或业务数据时返回 409，应改用「更新租户」把 status 设为 0（停用）。
                    不要删除默认租户：不带头的请求会落到它。
                    """)
    @DeleteMapping("/tenants/{id}")
    public void deleteTenant(
            @Parameter(description = "要删除的租户数字 id", required = true)
            @PathVariable long id) {
        assertLocalAdmin();
        service.deleteTenant(id);
    }

    @Operation(
            summary = "列出某租户下的项目",
            description = "路径必须带归属租户 id（不能做成 /api/projects）。返回该租户下全部项目。后续业务请求把项目 id 放到 X-Project-Id。")
    @GetMapping("/tenants/{tenantId}/projects")
    public List<ProjectResponse> listProjects(
            @Parameter(description = "归属租户数字 id", required = true)
            @PathVariable long tenantId) {
        return service.listProjects(tenantId);
    }

    @Operation(
            summary = "在指定租户下新建项目",
            description = """
                    项目挂在租户路径下。code 创建后不可通过更新改掉；status 新建时忽略，一律启用。
                    返回的 id 即之后业务接口请求头 X-Project-Id 的值。
                    """)
    @PostMapping("/tenants/{tenantId}/projects")
    public ProjectResponse createProject(
            @Parameter(description = "归属租户数字 id", required = true)
            @PathVariable long tenantId,
            @Valid @RequestBody ProjectRequest request) {
        assertLocalAdmin();
        return service.createProject(tenantId, request);
    }

    /**
     * 项目的读写一律挂在租户路径下。
     *
     * <p>不做成 {@code /api/projects/{id}}：那样服务端只能按项目 id 查，会产生一条
     * 没有租户过滤的读取，被 {@code TenantIsolationArchTest} 拦下 —— 而它拦得对，
     * 路径里带上归属租户才是正确的表达。
     */
    @Operation(
            summary = "更新项目名称、描述或状态",
            description = """
                    修改指定租户下某个项目的展示信息。路径必须同时带 tenantId 与项目 id，禁止只按项目 id 更新。
                    请求体 code 会被忽略（编码创建后不可改）。
                    status：1 启用，0 停用。有业务数据时不能删除，应改停用。
                    适用：改项目中文名/说明、停用不再使用的项目。不要用本接口改租户，也不要用它切换当前上下文（切换请改请求头并调 GET /api/context）。
                    """)
    @PutMapping("/tenants/{tenantId}/projects/{id}")
    public ProjectResponse updateProject(
            @Parameter(description = "归属租户数字 id，必须与该项目实际所属租户一致", required = true)
            @PathVariable long tenantId,
            @Parameter(description = "项目数字 id", required = true)
            @PathVariable long id,
            @Valid @RequestBody ProjectRequest request) {
        assertLocalAdmin();
        return service.updateProject(tenantId, id, request);
    }

    /** 其下还有业务数据时返回 409，提示改用停用。 */
    @Operation(
            summary = "删除项目",
            description = """
                    物理删除指定租户下的项目。其下仍有血缘或元数据时返回 409，应改用「更新项目」把 status 设为 0。
                    不要删除默认项目：不带头的请求会落到它。
                    """)
    @DeleteMapping("/tenants/{tenantId}/projects/{id}")
    public void deleteProject(
            @Parameter(description = "归属租户数字 id", required = true)
            @PathVariable long tenantId,
            @Parameter(description = "要删除的项目数字 id", required = true)
            @PathVariable long id) {
        assertLocalAdmin();
        service.deleteProject(tenantId, id);
    }

    private void assertLocalAdmin() {
        if (props.isMulti()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "多租户模式下租户由组织平台管理");
        }
    }
}
