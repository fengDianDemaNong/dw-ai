package com.dwai.lineage.service;

import com.dwai.lineage.auth.CurrentLocalUser;
import com.dwai.lineage.conf.LineageProperties;
import com.dwai.lineage.internal.OrgClient;
import com.dwai.lineage.persistence.ProjectRow;
import com.dwai.lineage.persistence.TenantAdminRepository;
import com.dwai.lineage.persistence.TenantRow;
import com.dwai.lineage.tenant.LineageContext;
import com.dwai.lineage.tenant.TenantContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 写操作的跨服务鉴权兜底。
 *
 * <p>数据地图的角色不存本地 —— 它在组织平台的 {@code project_members} 里，按
 * (项目, 用户, 产品) 一行一角色。所以「这个人能不能改这个项目的数据目录」这件事，
 * 只能问组织。
 *
 * <h2>为什么前端菜单收口了还要这一层</h2>
 *
 * <p>前端那份角色是壳通过 {@code #boot=} 带进来的，只用来把菜单画对；它<b>可以被改</b>，
 * 而且绕过前端直接 curl 更是完全没有它。菜单置灰是「不让人去点一个必然失败的地方」，
 * 不是门禁 —— 真正的门禁在这一层。两者是「两者都要」的关系，不是重复。
 *
 * <h2>为什么只接写操作</h2>
 *
 * <p>读接口本来就被租户隔离守着（{@code TenantInterceptor} + 各仓储的
 * {@code where tenant_id = ?}），再加一次跨服务往返，收益是每次读都多一跳、
 * 且组织不在时整个数据地图读不出来。写操作频率低、后果重，兜底的值在这里。
 *
 * <h2>不生效的两种模式</h2>
 *
 * <p>{@code standard} 与 {@code standalone} 是单租户部署：前者租户头整条被忽略、
 * 后端恒落在默认租户上，后者根本没有登录。这两种模式下没有「跨租户的合法用户读到别人
 * 数据」的问题，也就没有组织平台可以去问 —— 直接放行。这与 {@code SecurityConfig}
 * 里那段「standard 不受跨租户问题影响」的说明是同一个判断。
 */
@Component
public class LineageAuthz {

    /**
     * 本服务的产品码，权限表 {@code Perms} 的第一维。
     *
     * <p>与前端 {@code packages/engine/src/iam.ts} 的 {@code Product}、
     * {@code dw-lineage/ui/src/config/iam.ts} 的 {@code PRODUCT} 是同一个值。
     * 数据地图只有一个产品身份，所以这里是常量而不是配置项
     * （dw-org / dw-model 用 {@code dwai.product-code}，那两个进程可能换产品部署）。
     */
    private static final String PRODUCT = "metadata";

    private final LineageProperties props;
    private final OrgClient orgClient;
    private final TenantAdminRepository tenants;
    private final CurrentLocalUser current;

    public LineageAuthz(
            LineageProperties props,
            OrgClient orgClient,
            TenantAdminRepository tenants,
            CurrentLocalUser current) {
        this.props = props;
        this.orgClient = orgClient;
        this.tenants = tenants;
        this.current = current;
    }

    /**
     * 要求当前用户在当前项目下持有 {@code perm}，否则抛 403。
     *
     * <p>失败一律是拒绝：拿不到租户/项目编码是 400（本服务的镜像数据不全，是配置问题），
     * 组织答「不允许」或答不上来是 403 / 503（见 {@link OrgClient#check}）。
     * 没有「不确定就放行」的分支。
     *
     * @param perm 权限词，取 {@code catalog:admin} 或 {@code lineage:write}
     */
    public void require(String perm) {
        if (!props.isMulti()) return;
        LineageContext ctx = TenantContextHolder.require();
        String userId = current.requireSubject();
        String tenantCode = tenants.findTenant(ctx.tenantId()).map(TenantRow::code).orElse(null);
        String projectCode = tenants.findProject(ctx.tenantId(), ctx.projectId())
                .map(ProjectRow::code).orElse(null);
        if (tenantCode == null || projectCode == null) {
            // 组织按 code 认人，本地镜像里却没有 code：说明项目/租户还没同步全。
            // 报 400 而不是 403 —— 「你没权限」会让人去找项目管理员，而这件事要找运维。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "跨服务鉴权需要租户与项目编码");
        }
        Map<String, Object> r = orgClient.check(userId, tenantCode, projectCode, PRODUCT, perm);
        if (!Boolean.TRUE.equals(r.get("allow"))) {
            Object reason = r.get("reason");
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, reason == null ? "无权执行此操作" : String.valueOf(reason));
        }
    }
}
