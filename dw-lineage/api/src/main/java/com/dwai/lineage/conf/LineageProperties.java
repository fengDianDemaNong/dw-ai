package com.dwai.lineage.conf;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lineage")
public class LineageProperties {
    /**
     * standalone | standard | multi。空则回落到 {@link #deployMode}。
     *
     * <p><b>这里的默认值必须是空串</b>，与 {@code application.yml} 的
     * {@code ${LINEAGE_RUN_MODE:}} 一起构成回落链的第一级。写成非空值（比如 standard）
     * 会让 {@link #runMode()} 里的回落判断永远为真，{@link #deployMode} 被彻底架空 ——
     * dw-model 的 {@code DW_AI_MODE} 就这么静默失效过（同样的结构，见其
     * {@code application.yml} 里的注释）。两处都要留空。
     */
    private String runMode = "";
    /**
     * 兼容开关：standalone | standard | multi（环境变量 {@code DW_AI_MODE}）。
     *
     * <p>三个模块共用同一组部署变量，一次部署只配一份 —— 数据地图原先只认
     * {@code LINEAGE_RUN_MODE}，于是「给 org / model / lineage 统一设
     * {@code DW_AI_MODE=standalone}」在数据地图上<b>静默不生效</b>（落到默认的
     * standard，表现为「独立部署却要登录」）。这里补上回落，让统一开关真的统一。
     *
     * <p>优先级：{@code LINEAGE_RUN_MODE} 更具体，胜出；它没给才看本项。
     * 空/非法值的兜底方向见 {@link #runMode()}（朝「要认证」倒）。
     */
    private String deployMode = "";
    private String orgBaseUrl = "";
    private String publicBaseUrl = "";
    private String serviceBaseUrl = "";
    private String moduleToken = "";
    /** 只有 multi 用得到：校验组织签发的 JWT。 */
    private final Security security = new Security();

    public Security getSecurity() {
        return security;
    }

    /**
     * 与 dw-org / dw-model 共用同一组环境变量（{@code SECURITY_MODE} / {@code JWT_SECRET} /
     * {@code CASDOOR_*}），这样一次部署只配一份，也不会出现「组织用 A 密钥签发、
     * 数据地图用 B 密钥校验」这种一定 401 的错配。
     */
    public static class Security {
        /** dev = HS256 共享密钥；oidc = 校验 Casdoor JWT（RS256 + JWKS）。 */
        private String mode = "dev";
        private String jwtSecret = "dw-ai-dev-secret-change-me-please-32b";
        /**
         * standard 模式本地签发的 access JWT 有效期（秒）。
         *
         * <p>与 dw-model 取同一默认值 900（15 分钟）：短是为了让「踢人 / 改密」这类
         * 操作在可接受的时间内生效；再长一点也不会有人抱怨，但重启世代（{@code boot}）
         * 已经承担了「强制全体下线」的职责，所以没必要为了实时性把 TTL 压得更短。
         */
        private long jwtTtlSeconds = 900;
        /**
         * standard 模式 refresh token 的有效期（秒），刷新时滑动续期。
         *
         * <p>同样与 dw-model 对齐取 900。注意这是<b>滑动窗口</b>：只要用户在窗口内
         * 用过一次，过期时间就顺延，所以真正决定「多久不登录就得重新输密码」的是
         * 这个值，而不是 access TTL。
         */
        private long refreshTtlSeconds = 900;
        private final Casdoor casdoor = new Casdoor();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public boolean isOidc() {
            return "oidc".equalsIgnoreCase(mode);
        }

        public long getJwtTtlSeconds() {
            return jwtTtlSeconds;
        }

        public void setJwtTtlSeconds(long jwtTtlSeconds) {
            this.jwtTtlSeconds = jwtTtlSeconds;
        }

        public long getRefreshTtlSeconds() {
            return refreshTtlSeconds;
        }

        public void setRefreshTtlSeconds(long refreshTtlSeconds) {
            this.refreshTtlSeconds = refreshTtlSeconds;
        }

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }

        public Casdoor getCasdoor() {
            return casdoor;
        }

        public static class Casdoor {
            private String issuer = "";
            private String jwkSetUri = "";

            public String getIssuer() {
                return issuer;
            }

            public void setIssuer(String issuer) {
                this.issuer = issuer;
            }

            public String getJwkSetUri() {
                return jwkSetUri;
            }

            public void setJwkSetUri(String jwkSetUri) {
                this.jwkSetUri = jwkSetUri;
            }

            public boolean configured() {
                return issuer != null && !issuer.isBlank();
            }

            public String resolvedJwkSetUri() {
                if (jwkSetUri != null && !jwkSetUri.isBlank()) return jwkSetUri;
                if (!configured()) return "";
                return issuer.replaceAll("/$", "") + "/.well-known/jwks";
            }
        }
    }

    public String getRunMode() {
        return runMode;
    }

    public void setRunMode(String runMode) {
        this.runMode = runMode;
    }

    public String getDeployMode() {
        return deployMode;
    }

    public void setDeployMode(String deployMode) {
        this.deployMode = deployMode;
    }

    /**
     * 归一化后的运行模式。**只认显式的 {@code standalone}，其余一律 {@code standard}。**
     *
     * <p>取值优先级：{@code run-mode}（{@code LINEAGE_RUN_MODE}）→ {@code deploy-mode}
     * （{@code DW_AI_MODE}）→ 兜底 {@code standard}。前两级谁更具体谁胜：
     * 部署方给数据地图单独设了 {@code LINEAGE_RUN_MODE} 就以它为准，否则跟着三模块统一的
     * {@code DW_AI_MODE} 走。两级都空（或都是乱码）时落到 {@code standard}。
     *
     * <p>这个兜底方向是刻意的：{@code standalone} 是唯一<b>不做任何认证</b>的模式
     * （{@code /api/**} 全放行，见 {@code SecurityConfig}），所以它必须是「说出来才生效」，
     * 而不是「没说明白就落进去」。与 dw-model 的 {@code DwaiProperties.runMode()} 同向。
     *
     * <p>此前这里是反的：空值/非法值都回落 {@code standalone}。于是
     * {@code LINEAGE_RUN_MODE=}（<b>设了但为空</b>）会让数据地图静默变成无认证 ——
     * 注意 {@code ${LINEAGE_RUN_MODE:}} 只在变量<b>未设</b>时取默认值，
     * 设成空串时拿到的是空串，反倒绕过了 yml 里那个安全的默认值。
     * 这种失效在日志上看不出任何异常，只有断言能抓住：取值表见
     * {@code conf.LineagePropertiesRunModeTest}，「空值真的接到了门禁上」见
     * {@code controller.RunModeSmokeTest} 的 {@code BlankRunMode}。
     */
    public String runMode() {
        String raw = runMode != null && !runMode.isBlank() ? runMode : deployMode;
        if (raw == null || raw.isBlank()) return "standard";
        String m = raw.trim().toLowerCase();
        if ("standalone".equals(m) || "standard".equals(m) || "multi".equals(m)) return m;
        return "standard";
    }

    public boolean isStandalone() {
        return "standalone".equals(runMode());
    }

    public boolean isStandard() {
        return "standard".equals(runMode());
    }

    public boolean isMulti() {
        return "multi".equals(runMode());
    }

    public String getOrgBaseUrl() {
        return orgBaseUrl;
    }

    public void setOrgBaseUrl(String orgBaseUrl) {
        this.orgBaseUrl = orgBaseUrl;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getServiceBaseUrl() {
        return serviceBaseUrl;
    }

    public void setServiceBaseUrl(String serviceBaseUrl) {
        this.serviceBaseUrl = serviceBaseUrl;
    }

    public String serviceBaseUrl() {
        if (serviceBaseUrl != null && !serviceBaseUrl.isBlank()) return serviceBaseUrl.trim();
        return publicBaseUrl == null ? "" : publicBaseUrl.trim();
    }

    public String getModuleToken() {
        return moduleToken;
    }

    public void setModuleToken(String moduleToken) {
        this.moduleToken = moduleToken;
    }
}
