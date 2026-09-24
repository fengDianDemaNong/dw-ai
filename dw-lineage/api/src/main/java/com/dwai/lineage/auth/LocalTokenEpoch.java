package com.dwai.lineage.auth;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 进程启动世代，写入 standard 模式本地签发的 access JWT。
 *
 * <p>与 dw-model 的 {@code JwtSessionEpoch} 同一机制：进程每次启动生成新值，
 * 旧 access 在重启后整体失效（由 {@code SecurityConfig} 的解码器校验）；
 * refresh token 在库里、不带世代，可以换出带新世代的 access。
 *
 * <p><b>只对 standard 模式自己签发的令牌有意义</b>：multi 下令牌是组织签发的，
 * 令牌里的世代属于组织那次启动，与本进程无关，解码器刻意不校验（见 SecurityConfig）。
 */
@Component
public class LocalTokenEpoch {

    public static final String CLAIM = "boot";

    private final String id = UUID.randomUUID().toString();

    public String id() {
        return id;
    }
}
