package com.dwai.platform.auth;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** 进程启动时生成，写入 access JWT。重启后旧 access 失效；refresh token 在库里，可换带新 boot 的 access。 */
@Component
public class JwtSessionEpoch {
  public static final String CLAIM = "boot";

  private final String id = UUID.randomUUID().toString();

  public String id() {
    return id;
  }
}
