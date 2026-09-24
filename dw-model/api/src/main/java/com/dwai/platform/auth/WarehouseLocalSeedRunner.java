package com.dwai.platform.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.DwaiProperties;
import com.dwai.platform.meta.entity.ProjectEntity;
import com.dwai.platform.meta.entity.TenantEntity;
import com.dwai.platform.meta.entity.TenantLicenseEntity;
import com.dwai.platform.meta.entity.UserEntity;
import com.dwai.platform.meta.entity.UserTenantEntity;
import com.dwai.platform.meta.mapper.ProjectMapper;
import com.dwai.platform.meta.mapper.TenantLicenseMapper;
import com.dwai.platform.meta.mapper.TenantMapper;
import com.dwai.platform.meta.mapper.UserMapper;
import com.dwai.platform.meta.mapper.UserTenantMapper;
import com.dwai.platform.meta.SpecService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 仓建设独立 / 普通模式空库补一份本地租户。
 *
 * <p><b>隐含租户（standalone + standard）</b>：
 * 两种模式都需要 —— standalone 免登录需要默认上下文；standard 登录后
 * TenantFilter 也会从 implicitTenantId() 取 tenant，缺了它 standard
 * 模式下任何需要 tenant 的接口都会 403。
 *
 * <p><b>默认项目 / 本地用户（仅 standalone）</b>：
 * standalone 免登录需要一个默认项目让首页有东西可看，也需要一行**真实存在**的本地用户
 * （外键 `fk_pm_user` 与 tenantRole 都依赖它，理由见方法内的注释）；
 * standard 启动只有 admin，演示项目/成员走 {@code bin/seed-demo.sh} 手动灌。
 *
 * <p><b>multi 模式</b>：全部跳过 —— 项目由组织平台 fan-out，租户由
 * 平台管理，本进程不应自建任何隔离。
 */
@Order(2)
@Component
public class WarehouseLocalSeedRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(WarehouseLocalSeedRunner.class);

  private final DwaiProperties props;
  private final TenantMapper tenants;
  private final TenantLicenseMapper licenses;
  private final ProjectMapper projects;
  private final UserMapper users;
  private final UserTenantMapper userTenants;
  private final SpecService spec;

  public WarehouseLocalSeedRunner(
      DwaiProperties props,
      TenantMapper tenants,
      TenantLicenseMapper licenses,
      ProjectMapper projects,
      UserMapper users,
      UserTenantMapper userTenants,
      SpecService spec) {
    this.props = props;
    this.tenants = tenants;
    this.licenses = licenses;
    this.projects = projects;
    this.users = users;
    this.userTenants = userTenants;
    this.spec = spec;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (props.isMulti()) return;

    String tid = props.implicitTenantId();

    // ---------- 隐含租户 + 许可（standalone + standard） ----------
    if (tenants.selectById(tid) == null) {
      TenantEntity t = new TenantEntity();
      t.setId(tid);
      t.setCode("local");
      t.setName("本环境");
      t.setOwner("admin");
      t.setStatus("active");
      tenants.insert(t);

      TenantLicenseEntity lic = new TenantLicenseEntity();
      lic.setTenantId(tid);
      lic.setModules("[\"warehouse\"]");
      lic.setAiCaps("[\"spec_design\",\"spec_ask\",\"model_design\"]");
      licenses.insert(lic);
      log.info("已创建隐含租户 {} 与许可", tid);
    }

    // ---------- 把 admin 挂进隐含租户（standard 模式） ----------
    // standalone 不走这里（TenantFilter 直接给 tenantRole=admin）。
    // standard 下 admin 是 BootstrapAdminRunner 建的平台账号，
    // 但 TenantFilter 需要 user_tenant 记录来返回 tenantRole，
    // 否则前端会把 admin 当成"未加入租户"。
    if (props.isStandard()) {
      UserEntity admin = users.selectByUsername("admin");
      if (admin != null) {
        UserTenantEntity exist = userTenants.selectOne(Wrappers.<UserTenantEntity>lambdaQuery()
            .eq(UserTenantEntity::getUserId, admin.getId())
            .eq(UserTenantEntity::getTenantId, tid));
        if (exist == null) {
          UserTenantEntity ut = new UserTenantEntity();
          ut.setUserId(admin.getId());
          ut.setTenantId(tid);
          ut.setTenantRole("admin");
          userTenants.insert(ut);
          log.info("已将 admin 加入隐含租户 {}（角色 admin）", tid);
        }
      }
    }

    // ---------- 默认项目 + 本地用户（仅 standalone） ----------
    if (!props.isStandalone()) return;

    // 本地用户：独立模式没有登录，但**必须有一个真实存在的主体**。
    // TenantFilter 把上下文里的 userId 定成 TenantContext.STANDALONE_USER_ID，
    // 而建项目 / 加成员会把这个 id 落进 project_members.user_id —— 那一列有外键
    // 指向 users(id)（见 db/migration/*/V1__schema.sql 的 fk_pm_user）。
    // 少了这一行，独立模式下 `POST /api/projects` 不是 403 而是 500 外键违约：
    // AccessService.requireUser() 造的**内存**假用户把权限检查圆过去了，落库那一步才炸，
    // 表现就是「独立模式只能看、不能动」。
    if (users.selectById(TenantContext.STANDALONE_USER_ID) == null) {
      UserEntity local = new UserEntity();
      local.setId(TenantContext.STANDALONE_USER_ID);
      local.setTenantId(tid);
      local.setUsername(TenantContext.STANDALONE_USER_ID);
      // 不叫「访客」：独立模式是一个完整的本地部署，只是没有登录这一层，
      // 访问者拿到的是完整管理员能力。口径与数据地图同模式的占位主体一致
      // （CurrentLocalUser.PLACEHOLDER_STANDALONE 的显示名也是「独立模式」）。
      local.setDisplayName("独立模式");
      // 无登录：login() 在 standalone 直接 403，密码列留空也就无从校验。
      local.setPasswordHash(null);
      local.setStatus("active");
      local.setPlatformAdmin(false);
      users.insert(local);
      log.info("仓建设 standalone 模式已写入本地用户 {}", TenantContext.STANDALONE_USER_ID);
    }

    // 角色行：AuthService.currentMe() 是从 user_tenants 取 tenantRole 的。
    // 缺了它 me 会返回 tenantRole=null，前端据此把独立部署的管理员当成「未加入组织」——
    // 表现是「新增项目」按钮整块不渲染（projects.vue 的 v-if="isTenantAdmin"）、
    // 项目列表被成员表过滤成空。
    UserTenantEntity localUt = userTenants.selectOne(Wrappers.<UserTenantEntity>lambdaQuery()
        .eq(UserTenantEntity::getUserId, TenantContext.STANDALONE_USER_ID)
        .eq(UserTenantEntity::getTenantId, tid));
    if (localUt == null) {
      localUt = new UserTenantEntity();
      localUt.setUserId(TenantContext.STANDALONE_USER_ID);
      localUt.setTenantId(tid);
      localUt.setTenantRole("admin");
      userTenants.insert(localUt);
      log.info("仓建设 standalone 模式已把本地用户挂进隐含租户 {}（角色 admin）", tid);
    }

    if (projects.selectById("p-local") == null) {
      ProjectEntity p = new ProjectEntity();
      p.setId("p-local");
      p.setTenantId(tid);
      p.setCode("local_dw");
      p.setName("默认项目");
      p.setDescription("仓建设独立模式自动创建");
      p.setOwner("admin");
      p.setCreatedAt(LocalDate.now());
      p.setStatus("active");
      p.setEngines("[]");
      projects.insert(p);
    }

    if (projects.selectById("p-local") != null) {
      spec.bootstrap("p-local");
    }

    log.info("仓建设 standalone 模式已写入隐含租户 {} 与默认项目", tid);
  }
}
