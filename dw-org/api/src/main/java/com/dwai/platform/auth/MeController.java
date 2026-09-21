package com.dwai.platform.auth;

import com.dwai.platform.meta.AccessService;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.UserEntity;
import com.dwai.platform.meta.mapper.UserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping({"/api/me", "/api/v1/me"})
public class MeController {
  private final AccessService access;
  private final UserMapper users;
  private final PasswordEncoder passwords;

  public MeController(AccessService access, UserMapper users, PasswordEncoder passwords) {
    this.access = access;
    this.users = users;
    this.passwords = passwords;
  }

  @PutMapping
  public ApiModels.OrgUserDto update(@RequestBody ApiModels.ProfileReq req) {
    UserEntity u = access.requireUser();
    if (req == null || req.displayName() == null || req.displayName().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写显示名");
    }
    u.setDisplayName(req.displayName().trim());
    users.updateById(u);
    return new ApiModels.OrgUserDto(
        u.getId(), u.getUsername(), u.getDisplayName(), u.getStatus(), null, Boolean.TRUE.equals(u.getPlatformAdmin()));
  }

  @PutMapping("/password")
  public void password(@RequestBody ApiModels.PasswordReq req) {
    UserEntity u = access.requireUser();
    if (req == null || req.currentPassword() == null || req.newPassword() == null || req.newPassword().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写当前密码和新密码");
    }
    if (u.getPasswordHash() == null || !passwords.matches(req.currentPassword(), u.getPasswordHash())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前密码不正确");
    }
    if (req.newPassword().length() < 4) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码至少 4 位");
    }
    u.setPasswordHash(passwords.encode(req.newPassword()));
    users.updateById(u);
  }
}
