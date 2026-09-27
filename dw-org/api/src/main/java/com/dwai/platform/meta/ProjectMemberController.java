package com.dwai.platform.meta;

import com.dwai.platform.meta.dto.ApiModels;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 项目成员 —— 组织平台「项目壳」里「成员管理」页的后端。
 *
 * <h2>为什么单独一个 Controller，而不是挂进 {@link TenantAdminController}</h2>
 *
 * <p>那个类的映射是 {@code /api/tenants/{id}/...}，且每个端点都过
 * {@code access.requireTenantAdmin()}。成员管理不能照抄那道门槛：
 * <b>项目管理员未必是本组织管理员</b>（他可能只是一个普通租户成员，被派了项目的
 * {@code iam:member} 角色），挂过去会让整页对他 403。
 *
 * <p>其次，路径里的 tenantId 是**冗余的第二真相**：{@link AccessService#requireProject}
 * 已经拿 {@code TenantContext.tenantId()} 与项目的 {@code tenantId} 比对（不符抛 403），
 * 再加一个租户来源只会多一个可能与 JWT 不一致的入参。
 *
 * <h2>为什么路径长这样</h2>
 *
 * <p>{@code /projects/{projectId}/members} 与 dw-model 的 {@code MetaController} 逐字同形。
 * 两个前端因此共用同一段 client 代码 —— 顺带修掉一处既有静默 404：dw-org 前端
 * 早就有一条 {@code api.putMember} 打在这个路径上（见 {@code dw-org/ui/src/api/client.ts}
 * 的 {@code putMember}），而本服务的后端此前从没暴露过它。
 *
 * <p>路径不冲突：本服务现有的 {@code /api/v1/projects} 只有 {@code SessionController}
 * 的集合 GET，{@code /api/v1/projects/{id}/...} 这一段是空的。
 *
 * <p>跨服务守卫（{@code CrossServiceDesignGuardTest}）只管 {@code internal} 包里的路径字面量，
 * 本类在 {@code meta} 包下，不触发。
 */
@RestController
@RequestMapping({"/api", "/api/v1"})
public class ProjectMemberController {
  private final ProjectService projects;

  public ProjectMemberController(ProjectService projects) {
    this.projects = projects;
  }

  @GetMapping("/projects/{projectId}/members")
  public List<ApiModels.MemberDto> members(@PathVariable String projectId) {
    return projects.listMembers(projectId);
  }

  /**
   * 派/改一个人在本项目某个产品下的角色。
   *
   * <p>{@code userId} 以**路径变量**为准，body 里的那个忽略 —— 两处都收的话，
   * 不一致时谁赢取决于实现细节，而调用方会以为另一处生效了。与 dw-model 同一口径。
   *
   * <p>{@code product} 缺省由 {@link ProjectService#putMember} 兜成仓建设，所以老的
   * 「只传 role」的调用点仍然工作。
   */
  @PutMapping("/projects/{projectId}/members/{userId}")
  public ApiModels.MemberDto putMember(
      @PathVariable String projectId,
      @PathVariable String userId,
      @RequestBody ApiModels.MemberReq req) {
    return projects.putMember(projectId, new ApiModels.MemberReq(userId, req.product(), req.role()));
  }

  /**
   * 把一个人移出本项目 —— <b>所有产品</b>，不是某一个。
   *
   * <p>这是 {@link ProjectService#deleteMember} 的既定语义，页面上必须把范围说清楚。
   */
  @DeleteMapping("/projects/{projectId}/members/{userId}")
  public void deleteMember(@PathVariable String projectId, @PathVariable String userId) {
    projects.deleteMember(projectId, userId);
  }

  /**
   * 本项目可派的产品角色（角色下拉的选项）。
   *
   * <p>读侧判权与 {@link #members} 一致（{@code spec:read}）—— 看得到成员名单的人，
   * 也就看得到「有哪些角色可选」。
   */
  @GetMapping("/projects/{projectId}/member-roles")
  public List<Map<String, Object>> memberRoles(@PathVariable String projectId) {
    return projects.memberRoles(projectId);
  }
}
