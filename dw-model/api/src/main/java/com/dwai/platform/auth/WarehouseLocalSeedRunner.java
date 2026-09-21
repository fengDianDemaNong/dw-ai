package com.dwai.platform.auth;

import com.dwai.platform.DwaiProperties;
import com.dwai.platform.meta.entity.ProjectEntity;
import com.dwai.platform.meta.entity.ProjectMemberEntity;
import com.dwai.platform.meta.entity.TenantEntity;
import com.dwai.platform.meta.entity.TenantLicenseEntity;
import com.dwai.platform.meta.entity.UserEntity;
import com.dwai.platform.meta.entity.UserTenantEntity;
import com.dwai.platform.meta.mapper.ProjectMapper;
import com.dwai.platform.meta.mapper.ProjectMemberMapper;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 仓建设独立 / 普通模式空库补一份本地租户、默认项目和演示账号。
 * 多租户不灌：项目由组织平台 fan-out。
 */
@Order(2)
@Component
public class WarehouseLocalSeedRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(WarehouseLocalSeedRunner.class);
  private static final String PASSWORD = "123456";

  private final DwaiProperties props;
  private final TenantMapper tenants;
  private final TenantLicenseMapper licenses;
  private final UserMapper users;
  private final UserTenantMapper userTenants;
  private final ProjectMapper projects;
  private final ProjectMemberMapper members;
  private final PasswordEncoder passwords;
  private final SpecService spec;

  public WarehouseLocalSeedRunner(
      DwaiProperties props,
      TenantMapper tenants,
      TenantLicenseMapper licenses,
      UserMapper users,
      UserTenantMapper userTenants,
      ProjectMapper projects,
      ProjectMemberMapper members,
      PasswordEncoder passwords,
      SpecService spec) {
    this.props = props;
    this.tenants = tenants;
    this.licenses = licenses;
    this.users = users;
    this.userTenants = userTenants;
    this.projects = projects;
    this.members = members;
    this.passwords = passwords;
    this.spec = spec;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!props.isWarehouseOnly() || props.isMulti()) return;
    String tid = props.implicitTenantId();
    if (tenants.selectById(tid) == null) {
      TenantEntity t = new TenantEntity();
      t.setId(tid);
      t.setCode("local");
      t.setName("本环境");
      t.setOwner("张三");
      t.setStatus("active");
      tenants.insert(t);

      TenantLicenseEntity lic = new TenantLicenseEntity();
      lic.setTenantId(tid);
      lic.setModules("[\"warehouse\"]");
      lic.setAiCaps("[\"spec_design\",\"spec_ask\",\"model_design\"]");
      licenses.insert(lic);
    }

    if (projects.selectById("p-local") == null) {
      ProjectEntity p = new ProjectEntity();
      p.setId("p-local");
      p.setTenantId(tid);
      p.setCode("local_dw");
      p.setName("默认项目");
      p.setDescription("仓建设独立/普通模式自动创建");
      p.setOwner("张三");
      p.setCreatedAt(LocalDate.now());
      p.setStatus("active");
      p.setEngines("[]");
      projects.insert(p);
    }

    if (props.isStandard()) {
      addUser("张三", "admin", "admin");
      addUser("李四", "member", "modeler");
      addUser("王五", "member", "viewer");
    }

    if (projects.selectById("p-local") != null) {
      spec.bootstrap("p-local");
    }

    log.info("仓建设 {} 模式已写入本地租户 {} 与默认项目", props.runMode(), tid);
  }

  private void addUser(String name, String tenantRole, String projectRole) {
    if (users.selectByUsername(name) != null) return;
    UserEntity u = new UserEntity();
    u.setId(name);
    u.setTenantId(props.implicitTenantId());
    u.setUsername(name);
    u.setDisplayName(name);
    u.setPasswordHash(passwords.encode(PASSWORD));
    u.setStatus("active");
    u.setPlatformAdmin(false);
    users.insert(u);

    UserTenantEntity ut = new UserTenantEntity();
    ut.setUserId(name);
    ut.setTenantId(props.implicitTenantId());
    ut.setTenantRole(tenantRole);
    userTenants.insert(ut);

    ProjectMemberEntity pm = new ProjectMemberEntity();
    pm.setProjectId("p-local");
    pm.setUserId(name);
    pm.setRole(projectRole);
    members.insert(pm);
  }
}
