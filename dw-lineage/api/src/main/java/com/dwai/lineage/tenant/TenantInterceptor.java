package com.dwai.lineage.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.dwai.lineage.conf.LineageProperties;
import com.dwai.lineage.internal.OrgProjectPuller;
import com.dwai.lineage.persistence.ProjectRow;
import com.dwai.lineage.persistence.TenantAdminRepository;
import com.dwai.lineage.persistence.TenantRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;

/**
 * 在 HTTP 层解析租户上下文，塞进 {@link TenantContextHolder}。
 *
 * <p>取值来源为请求头 {@code X-Tenant-Code} / {@code X-Project-Code}，兼容
 * {@code X-Tenant-Id} / {@code X-Project-Id}：数字按本地 id 用，非数字按
 * {@code tenant.code} / {@code project.code} 解析（技术方案 §3.3）。
 *
 * <h2>standard 模式：固定单租户，租户头一律忽略</h2>
 *
 * <p>普通模式是「自己装一套、自己用」，一套库一份数据，界面与接口都不提供租户维度
 * （见 {@code TenantAdminController}）。所以这里<b>不看</b> {@code X-Tenant-Id} /
 * {@code X-Tenant-Code}，带了什么都强制默认租户 —— 前端已经不发这个头了，但手工 curl
 * 带上就能读写别的租户、写出孤儿数据（见 {@code KNOWN_ISSUES.md}），所以必须在服务端挡，
 * 只靠「前端不发」不算收紧。
 *
 * <p><b>项目维度仍然解析</b>：standard 下项目是保留的数据组织方式，一个部署内可以分多个项目。
 *
 * <h2>multi 模式：租户头是必填的，缺省不再回落到默认租户</h2>
 *
 * <p>独立模式下请求头缺失就落到默认租户，这是刻意的（前端未登录时也要能看页面）。
 * 但 multi 模式下<strong>不能</strong>这样做：默认租户是本地主键 {@code 1}，
 * 与组织侧的租户没有必然关系，静默回落等于「把这次请求当成租户 1 的身份放行」，
 * 正是 §3.3 禁止的形态。所以 multi 下缺头直接 400，让问题在调用方暴露。
 *
 * <p>同样地，编码解析不到时**不会自动建租户**，而是先按需去组织拉一次镜像
 * （见 {@link OrgProjectPuller}）—— 但拉的也只是<b>组织确实有</b>的那个租户/项目。
 * 凭请求头里的字符串就地建一个租户，只会把「编码写错」变成「静默多出一个租户」，
 * 是纯粹的数据污染。
 *
 * <h2>不要求租户头的接口</h2>
 *
 * <p>两类：运行模式探测（不知道模式就不知道该不该带租户头，要求它是循环依赖），
 * 以及跨租户管理面（作用对象由路径参数指定，本来就<b>不读</b>租户上下文，见
 * {@code TenantAdminController}）。
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String PROJECT_HEADER = "X-Project-Id";
    public static final String TENANT_CODE_HEADER = "X-Tenant-Code";
    public static final String PROJECT_CODE_HEADER = "X-Project-Code";

    /** 不需要租户上下文解析的路径（模式探测、跨租户管理面），前缀匹配。 */
    private static final List<String> NO_TENANT_CONTEXT = List.of(
            "/api/runtime", "/api/v1/runtime",
            "/api/tenants");

    /**
     * 需要解析、但不强制带头（解析不到就回落默认值）。
     *
     * <p>{@code /api/context} 的职责恰恰是「回报请求头解析出了什么」，所以它必须解析；
     * 但它又要在头缺失/指向不存在的租户时正常应答（返回 {@code tenantExists:false}），
     * 不能因为 multi 就 400 —— 那等于把诊断接口本身关掉。
     */
    private static final List<String> LENIENT_PATHS = List.of("/api/context");

    private enum Rule {
        /** 正常解析，multi 下缺头即拒。 */
        STRICT,
        /** 正常解析，但缺头时回落默认值。 */
        LENIENT,
        /** 不解析，直接给默认上下文。 */
        SKIP
    }

    private static final Logger log = LoggerFactory.getLogger(TenantInterceptor.class);

    private final long defaultTenantId;
    private final long defaultProjectId;
    private final TenantAdminRepository repository;
    private final LineageProperties props;
    private final OrgProjectPuller puller;

    public TenantInterceptor(
            @Value("${tenant.default-tenant-id:1}") long defaultTenantId,
            @Value("${tenant.default-project-id:1}") long defaultProjectId,
            TenantAdminRepository repository,
            LineageProperties props,
            OrgProjectPuller puller) {
        this.defaultTenantId = defaultTenantId;
        this.defaultProjectId = defaultProjectId;
        this.repository = repository;
        this.props = props;
        this.puller = puller;
    }

    /** 租户上下文无效。不用 ResponseStatusException：它要经异常解析器，这里直接写响应更确定。 */
    private static final class Rejected extends RuntimeException {
        Rejected(String message) {
            super(message);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Rule rule = ruleFor(request.getRequestURI());
        try {
            if (rule == Rule.SKIP) {
                TenantContextHolder.set(new LineageContext(defaultTenantId, defaultProjectId));
                return true;
            }
            boolean strict = rule == Rule.STRICT && props.isMulti();
            // 组织不再推送项目镜像（见 OrgProjectPuller 的类注释），本地解析不出来时
            // 先自己来拉一次。必须放在解析**之前**，且必须两头都看：
            // - 租户解析失败时 resolveTenant 直接抛「租户编码未同步」，根本走不到项目；
            // - 全新部署下租户可能已同步、项目还没，反过来也一样。
            // 所以判据是「这一段要不要去问组织」，而不是「项目在不在」。
            String rawTenant = firstHeader(request, TENANT_CODE_HEADER, TENANT_HEADER);
            String rawProject = firstHeader(request, PROJECT_CODE_HEADER, PROJECT_HEADER);
            if (props.isMulti() && (rawTenant != null || rawProject != null)) {
                // 本地缺东西 → 同步拉、拉不到就 503（required=true）；
                // 本地有、只是该跟组织对账了 → 后台刷一次，本次请求不受影响（required=false）。
                // 后者是「组织改了项目名/撤了许可，模块多久跟上」的唯一通道，不能省。
                boolean required = needsPull(rawTenant, rawProject);
                try {
                    puller.ensure(rawTenant, rawProject, required);
                } catch (OrgProjectPuller.Unavailable e) {
                    // 与下面「编码未同步」的 400 分开：那是「去组织里看看项目建了没」，
                    // 这个是「组织自己挂了」。混成一个，运维会拿着编码去查配置。
                    log.warn("组织不可达，无法解析租户上下文：{}", e.getMessage());
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"组织平台不可用\",\"detail\":\""
                            + e.getMessage() + "\",\"status\":503}");
                    return false;
                }
            }
            // standard 固定单租户：租户头一律忽略，只认默认租户。前端已经不发这个头，
            // 但手工 curl 带上就能写出孤儿数据（KNOWN_ISSUES 记过这个缺口），
            // 所以必须在服务端挡 —— 只靠前端不发不算收紧。
            long tenantId = props.isStandard() ? defaultTenantId : resolveTenant(request, strict);
            long projectId = resolveProject(request, tenantId, strict);
            TenantContextHolder.set(new LineageContext(tenantId, projectId));
            return true;
        } catch (Rejected e) {
            log.warn("拒绝 {} {}：{}", request.getMethod(), request.getRequestURI(), e.getMessage());
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"租户上下文无效\",\"detail\":\"" + e.getMessage() + "\",\"status\":400}");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContextHolder.clear();
    }

    private static boolean matches(List<String> prefixes, String uri) {
        if (uri == null) return false;
        for (String prefix : prefixes) {
            if (uri.equals(prefix) || uri.startsWith(prefix)) return true;
        }
        return false;
    }

    private static Rule ruleFor(String uri) {
        if (matches(NO_TENANT_CONTEXT, uri)) return Rule.SKIP;
        if (matches(LENIENT_PATHS, uri)) return Rule.LENIENT;
        return Rule.STRICT;
    }

    /**
     * 这一段租户/项目在本地能不能解析出来；解析不出来才值得去问组织。
     *
     * <p><b>为什么不能无条件拉</b>（这是本轮被测试抓出来的一个真缺陷）：
     * <ul>
     *   <li>{@code X-Tenant-Id: 1} / {@code X-Project-Id: 1} 这种<b>数字本地 id</b> 是既有的
     *       合法用法（{@code TenantHeaderContractTest.numericLocalIdsStillWork}），本地就有，
     *       每个请求都去问组织纯属浪费；</li>
     *   <li>更要紧的是，组织不可达时本地能解析的请求<b>必须照常服务</b> —— 数据地图的核心能力
     *       （SQL 解析、血缘、元数据）不依赖组织，连不上组织就整个模块 503 等于把它绑死在 org 上
     *       （{@code RunModeSmokeTest.servesWithTokenAndTenantHeaderWhenOrgIsUnreachable}）。</li>
     * </ul>
     *
     * <p>判据与下面两个 resolver 逐字一致（数字按本地 id、非数字按 code），
     * 只是失败时返回「要不要问」而不是抛 {@link Rejected} —— 两处若要改口径必须一起改，
     * 否则会出现「拦截器说解析得出来、resolver 说解析不出来」的分叉。
     */
    private boolean needsPull(String rawTenant, String rawProject) {
        Long tenantId = localTenantId(rawTenant);
        if (rawTenant != null && tenantId == null) return true;   // 租户没落地：值得问一次
        if (tenantId == null) return false;                       // 连租户都没给：strict 下会 400，问了也没用
        if (rawProject == null) return false;                     // 没带项目：没什么可补
        return localProjectId(tenantId, rawProject) == null;
    }

    /** 与 {@link #resolveTenant} 同一套判据，解析不出来时返回 {@code null}。 */
    private Long localTenantId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        try {
            long id = Long.parseLong(v);
            return repository.findTenant(id).map(TenantRow::id).orElse(null);
        } catch (NumberFormatException e) {
            return repository.findTenantByCode(v).map(TenantRow::id).orElse(null);
        }
    }

    /** 与 {@link #resolveProject} 同一套判据，解析不出来时返回 {@code null}。 */
    private Long localProjectId(long tenantId, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        try {
            long id = Long.parseLong(v);
            return repository.findProject(tenantId, id).map(ProjectRow::id).orElse(null);
        } catch (NumberFormatException e) {
            return repository.findProjectByCode(tenantId, v).map(ProjectRow::id).orElse(null);
        }
    }

    private long resolveTenant(HttpServletRequest request, boolean strict) {
        String raw = firstHeader(request, TENANT_CODE_HEADER, TENANT_HEADER);
        if (raw == null || raw.isBlank()) {
            if (strict) {
                throw new Rejected("multi 模式下必须带 " + TENANT_CODE_HEADER + "，不能回落到默认租户");
            }
            return defaultTenantId;
        }
        String v = raw.trim();
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return repository.findTenantByCode(v)
                    .map(TenantRow::id)
                    .orElseThrow(() -> new Rejected("租户编码未同步"));
        }
    }

    private long resolveProject(HttpServletRequest request, long tenantId, boolean strict) {
        String raw = firstHeader(request, PROJECT_CODE_HEADER, PROJECT_HEADER);
        if (raw == null || raw.isBlank()) {
            if (strict) {
                throw new Rejected("multi 模式下必须带 " + PROJECT_CODE_HEADER + "，不能回落到默认项目");
            }
            return defaultProjectId;
        }
        String v = raw.trim();
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return repository.findProjectByCode(tenantId, v)
                    .map(ProjectRow::id)
                    .orElseThrow(() -> new Rejected("项目编码未同步"));
        }
    }

    private static String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String v = request.getHeader(name);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
