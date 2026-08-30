package com.dwai.platform.auth;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** 进程启动时生成，写入 JWT。服务重启后旧令牌全部失效。 */
@Component
public class JwtSessionEpoch {
  public static final String CLAIM = "boot";

  private final String id = UUID.randomUUID().toString();

  public String id() {
    return id;
  }
}
