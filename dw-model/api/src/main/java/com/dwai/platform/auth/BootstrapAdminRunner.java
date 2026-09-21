package com.dwai.platform.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.DwaiProperties;
import com.dwai.platform.meta.entity.UserEntity;
import com.dwai.platform.meta.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 没有平台用户时创建初始 admin。不灌演示租户 / 张三。 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

  private final UserMapper users;
  private final PasswordEncoder passwords;
  private final DwaiProperties props;

  public BootstrapAdminRunner(UserMapper users, PasswordEncoder passwords, DwaiProperties props) {
    this.users = users;
    this.passwords = passwords;
    this.props = props;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (props.isWarehouseOnly()) {
      return;
    }
    long admins = users.selectCount(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getPlatformAdmin, true));
    if (admins > 0) {
      return;
    }
    DwaiProperties.Bootstrap boot = props.getBootstrap();
    String username = blank(boot.getAdminUsername()) ? "admin" : boot.getAdminUsername().trim();
    String password = boot.getAdminPassword();
    if (blank(password)) {
      log.warn("库中尚无平台用户，且 BOOTSTRAP_ADMIN_PASSWORD 为空，跳过创建。请在平台后台或 seed 之前先配置初始密码。");
      return;
    }
    UserEntity exist = users.selectByUsername(username);
    if (exist != null) {
      log.warn("用户名 {} 已存在但不是平台用户，未自动提升。请手工指定一名平台管理员。", username);
      return;
    }
    UserEntity u = new UserEntity();
    u.setId("admin".equals(username) ? "admin" : "u-bootstrap");
    u.setUsername(username);
    u.setDisplayName(blank(boot.getAdminDisplayName()) ? username : boot.getAdminDisplayName().trim());
    u.setPasswordHash(passwords.encode(password));
    u.setStatus("active");
    u.setPlatformAdmin(true);
    users.insert(u);
    log.info("已创建初始平台用户 {}，登录后请尽快改密。演示租户仍须执行 seed.sh。", username);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
