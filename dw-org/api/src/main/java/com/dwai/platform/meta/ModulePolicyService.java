package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.TenantEntity;
import com.dwai.platform.meta.entity.TenantLicenseEntity;
import com.dwai.platform.meta.mapper.TenantLicenseMapper;
import com.dwai.platform.meta.support.Jsons;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作台「模块管理」页：租户在平台开通范围内的第二层控制（启停 + 可见范围）。
 *
 * <h2>与「许可」的关系</h2>
 *
 * <p>{@code tenant_licenses.modules} 是<b>平台</b>给这个租户开通的上限（平台后台改），
 * {@code tenant_licenses.module_policies} 是本组织在这个上限内的启停与可见范围（本页改）。
 * <b>两个独立的开关</b>：平台把某模块重新开通后，租户之前关掉的状态保留 —— 平台不该悄悄
 * 替租户做决定。所以这里<b>只列已开通的模块</b>（上限之外的根本不该出现），也<b>只允许写
 * 已开通的模块</b>（写了就 400）。
 *
 * <p>策略里若留着一条「平台已关闭的模块」，读侧因为先判许可而自然无效 —— 本类不去清理它，
 * 因为平台可能只是临时关掉，下次开通就该恢复原设置。
 *
 * <h2>读侧的口径</h2>
 *
 * <p>本页读的是「显示值」（没配过就给原型默认值，好让管理员一进来有个合理起点）；
 * 而侧栏生效侧读的是「没配过 = 不判」（见 {@link NavNodeService} 第三层注释）。
 * 两者<b>刻意不对称</b>，本类的 {@link ApiModels.ModuleRowDto#explicit()} 就是给前端
 * 提示这件事用的。
 *
 * <p>与 {@link NavNodeService#licensedProducts} 一样走 <b>fail-closed</b>：没有许可行 =
 * 一个模块都没开，页面显示空态。绝不借 {@code AccessService.licensedProducts} 那份
 * （它 fail-open 回落 {@code ["warehouse"]}，是给「建项目 / 进项目」用的）—— 借了会出现
 * 「模块管理页列着 warehouse，而侧栏里什么都没有」。
 */
@Service
public class ModulePolicyService {

  private final TenantLicenseMapper licenses;
  private final AccessService access;

  public ModulePolicyService(TenantLicenseMapper licenses, AccessService access) {
    this.licenses = licenses;
    this.access = access;
  }

  /** 本组织可配的模块及其当前设置（只列平台已开通的）。 */
  public List<ApiModels.ModuleRowDto> list(String tenantId) {
    requireTenant(tenantId);
    access.requireTenantAdmin();
    return rows(licenses.selectById(tenantId));
  }

  /**
   * 保存策略 —— <b>全量覆盖</b>。
   *
   * <p>全量而不是逐模块 PATCH：页面上每改一格就提交一次，若用增量语义，两次并发提交会
   * 互相覆盖掉对方没提的那一格；全量则「最后一次提交就是最终状态」，与页面的所见即所得一致。
   *
   * <p>空数组 = 清空全部策略 = 回到「没配过」（侧栏不再判可见范围）。这是<b>有意保留</b>的
   * 出口：管理员要能撤回自己做过的一轮设置。
   */
  @Transactional
  public List<ApiModels.ModuleRowDto> put(String tenantId, List<ApiModels.ModulePolicyDto> body) {
    requireTenant(tenantId);
    access.requireTenantAdmin();
    TenantLicenseEntity lic = licenses.selectById(tenantId);
    List<String> licensed = licensedOf(lic);

    List<ApiModels.ModulePolicyDto> clean = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (ApiModels.ModulePolicyDto p : body == null ? List.<ApiModels.ModulePolicyDto>of() : body) {
      String product = p == null ? "" : nz(p.product());
      if (!licensed.contains(product)) {
        // 越界 400 而不是静默忽略：静默的话管理员会以为设置生效了，而侧栏毫无变化。
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "模块「" + product + "」未对本报组织开通");
      }
      if (!seen.add(product)) continue; // 同一 product 提了两次：以第一条为准
      String visibleTo = nz(p.visibleTo());
      if (!visibleTo.isEmpty() && !NavNodeService.VISIBLE_TO.contains(visibleTo)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "可见范围「" + visibleTo + "」不认识");
      }
      clean.add(new ApiModels.ModulePolicyDto(product, p.enabled(),
          visibleTo.isEmpty() ? NavNodeService.DEFAULT_VISIBLE_TO : visibleTo));
    }

    // 没有许可行 = 这个租户一个模块都没开通 => 上面任何非空 body 都已经 400 了，
    // 走到这里的只可能是空 body（清空），而没有行就没有东西可清。
    if (lic != null) {
      String next = clean.isEmpty() ? null : Jsons.toJson(clean);
      // 内存里也要跟着改：下面的 rows(lic) 读的就是它，不然响应会回一份已经不存在于库里的策略。
      lic.setModulePolicies(next);
      if (next == null) {
        // **清空这一支必须显式写 NULL，不能用 updateById**：MyBatis-Plus 的 updateById
        // 默认忽略 null 字段（FieldStrategy.NOT_NULL），于是「清空策略」会静默不生效 ——
        // 接口回 200、响应里也确实是空的，而库里那一行原封不动。用户的症状是
        // 「清空后一刷新，设置又回来了」，而任何一处日志都不会提。
        licenses.update(null, Wrappers.<TenantLicenseEntity>lambdaUpdate()
            .set(TenantLicenseEntity::getModulePolicies, null)
            .eq(TenantLicenseEntity::getTenantId, tenantId));
      } else {
        licenses.updateById(lic);
      }
    }
    return rows(lic);
  }

  /** 已开通的模块 → 表格行。没配过的给显示用默认值并标 {@code explicit = false}。 */
  private List<ApiModels.ModuleRowDto> rows(TenantLicenseEntity lic) {
    List<String> licensed = licensedOf(lic);
    Map<String, ApiModels.ModulePolicyDto> stored = new HashMap<>();
    for (ApiModels.ModulePolicyDto p : parse(lic == null ? null : lic.getModulePolicies())) {
      stored.put(p.product(), p);
    }
    List<ApiModels.ModuleRowDto> out = new ArrayList<>(licensed.size());
    for (String product : licensed) {
      ApiModels.ModulePolicyDto p = stored.get(product);
      boolean explicit = p != null;
      if (p == null) {
        p = new ApiModels.ModulePolicyDto(product, true, defaultVisibleTo(product));
      }
      out.add(new ApiModels.ModuleRowDto(product, p.enabled(), p.visibleTo(), explicit));
    }
    return out;
  }

  /** 该租户开通的模块；**没有许可行 = 一个都没开**（fail-closed，见类注释）。 */
  private List<String> licensedOf(TenantLicenseEntity lic) {
    if (lic == null) return List.of();
    List<String> modules = Jsons.strings(lic.getModules());
    return modules == null ? List.of() : modules;
  }

  /** 库里存着的策略（逐项丢弃非法项，理由同 {@code NavNodeService.policiesOf}）。 */
  private List<ApiModels.ModulePolicyDto> parse(String raw) {
    List<Map<String, Object>> items = Jsons.maps(raw);
    List<ApiModels.ModulePolicyDto> out = new ArrayList<>(items.size());
    for (Map<String, Object> item : items) {
      String product = nz(item.get("product") == null ? null : String.valueOf(item.get("product")));
      if (!ProductCodes.LICENSE_MODULES.contains(product)) continue;
      String visibleTo = nz(item.get("visibleTo") == null ? null : String.valueOf(item.get("visibleTo")));
      if (!visibleTo.isEmpty() && !NavNodeService.VISIBLE_TO.contains(visibleTo)) continue;
      out.add(new ApiModels.ModulePolicyDto(
          product,
          !Boolean.FALSE.equals(item.get("enabled")),
          visibleTo.isEmpty() ? defaultVisibleTo(product) : visibleTo));
    }
    return out;
  }

  /** 默认可见范围（显示用初值）—— 与 {@code NavNodeService.defaultVisibleTo} 同一口径。 */
  private static String defaultVisibleTo(String product) {
    return "warehouse".equals(product) ? "all_members" : NavNodeService.DEFAULT_VISIBLE_TO;
  }

  private void requireTenant(String tenantId) {
    TenantEntity t = access.requireTenant();
    if (!t.getId().equals(tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "组织不匹配");
    }
  }

  private static String nz(String s) {
    return s == null ? "" : s.trim();
  }
}
