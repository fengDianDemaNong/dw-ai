package com.dwai.platform.auth;

import com.dwai.platform.DwaiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer.FrameOptionsConfig;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class SecurityConfig {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, TenantFilter tenantFilter, DwaiProperties props) throws Exception {
    String ancestors = frameAncestors(props);
    http.csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .headers(h -> {
          // 默认的 X-Frame-Options: DENY 会让门户里的 iframe 直接白屏，而开发态是 Vite
          // 发的页面、没有这个头 —— 也就是说这个问题**只在打包态显形**。
          // 换成 CSP 的 frame-ancestors：它支持多个来源，正是嵌入门户需要的。
          //
          // 白名单表达不出来时什么都不动（保持 DENY）：宁可嵌不进去，也不能在配置
          // 不完整时静默地允许任何人嵌。与 dw-lineage 的同名实现同一口径。
          if (ancestors == null) return;
          h.frameOptions(FrameOptionsConfig::disable)
              .contentSecurityPolicy(csp -> csp.policyDirectives("frame-ancestors " + ancestors));
        })
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(reg -> {
          var authz = reg.requestMatchers(
                  "/api/auth/config",
                  "/api/v1/auth/config",
                  "/api/auth/login",
                  "/api/v1/auth/login",
                  "/api/auth/refresh",
                  "/api/v1/auth/refresh",
                  "/api/auth/logout",
                  "/api/v1/auth/logout",
                  "/api/runtime",
                  "/api/v1/runtime",
                  "/api/manifest",
                  "/api/v1/manifest",
                  "/actuator/health",
                  "/api/health",
                  "/api/v1/health",
                  "/internal/v1/**")
              .permitAll()
              .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
          if (props.isStandalone()) {
            authz.requestMatchers("/api/**").permitAll().anyRequest().permitAll();
          } else {
            authz.requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll();
          }
        })
        .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
        .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  /**
   * 把 {@code dwai.web.cors-origins} 翻译成 CSP {@code frame-ancestors} 的取值。
   *
   * <p>两套语法**不一样**，不能直接搬：CORS 那边支持 {@code http://localhost:*} 这种
   * 端口通配（{@code allowedOriginPatterns}），CSP 的 frame-ancestors 只接受
   * {@code scheme://host:port} 或一个裸的 {@code *}。把端口通配原样写进去是个**非法指令**，
   * 浏览器会整条忽略 —— 表现是「配了白名单，结果谁都能嵌」，比不配还糟。所以带通配的
   * 条目直接剔除并告警，只保留能精确表达的那些。
   *
   * @return 可直接放进指令的来源列表；{@code *} 表示不限来源；
   *         表达不出来（白名单为空或全是通配写法）时返回 {@code null}，调用方据此保持 DENY
   */
  private String frameAncestors(DwaiProperties props) {
    String raw = props.getWeb().getCorsOrigins();
    List<String> usable = new ArrayList<>();
    for (String item : (raw == null ? "" : raw).split(",")) {
      String origin = item.trim().replaceAll("/$", "");
      if (origin.isEmpty()) continue;
      if ("*".equals(origin)) return "*";
      if (origin.contains("*")) {
        log.warn("cors-origins 里的 {} 是通配写法，CSP frame-ancestors 表达不了，已忽略；"
            + "要让它的页面嵌入本服务，请写明确来源", origin);
        continue;
      }
      usable.add(origin);
    }
    if (usable.isEmpty()) {
      log.warn("cors-origins 里没有可精确表达的来源，本服务页面不能被他站 iframe 嵌入"
          + "（保持 X-Frame-Options: DENY）。门户集成时把它设成门户的页面地址即可");
      return null;
    }
    return String.join(" ", usable);
  }

  @Bean
  TenantFilter tenantFilter(
      DwaiProperties props,
      com.dwai.platform.meta.mapper.TenantMapper tenants,
      com.dwai.platform.meta.mapper.UserMapper users,
      com.dwai.platform.meta.mapper.UserTenantMapper userTenants,
      com.dwai.platform.meta.mapper.PlatformAccessMapper access,
      com.dwai.platform.meta.mapper.TenantGrantMapper grants,
      com.dwai.platform.meta.mapper.ProjectMapper projects,
      com.dwai.platform.internal.OrgProjectPuller puller) {
    return new TenantFilter(props, tenants, users, userTenants, access, grants, projects, puller);
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /** 避免 Filter bean 被容器再挂到 Servlet 链上（只走 Security 链）。 */
  @Bean
  FilterRegistrationBean<TenantFilter> tenantFilterRegistration(TenantFilter filter) {
    FilterRegistrationBean<TenantFilter> reg = new FilterRegistrationBean<>(filter);
    reg.setEnabled(false);
    return reg;
  }

  @Bean
  JwtDecoder jwtDecoder(DwaiProperties props, JwtSessionEpoch epoch) {
    DwaiProperties.Security sec = props.getSecurity();
    if (sec.isOidc() && sec.getCasdoor().configured()) {
      // Casdoor 默认 RS256；启动时不拉 metadata，避免没装 Casdoor 就起不来
      return NimbusJwtDecoder.withJwkSetUri(sec.getCasdoor().resolvedJwkSetUri())
          .jwsAlgorithm(SignatureAlgorithm.RS256)
          .build();
    }
    byte[] secret = sec.getJwtSecret().getBytes(StandardCharsets.UTF_8);
    SecretKeySpec key = new SecretKeySpec(secret, "HmacSHA256");
    JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    return token -> {
      Jwt jwt = decoder.decode(token);
      // 仓建设 multi 认组织签发的 JWT，不校验本进程启动世代
      if (!(props.isWarehouseOnly() && props.isMulti())) {
        String boot = jwt.getClaimAsString(JwtSessionEpoch.CLAIM);
        if (boot == null || !boot.equals(epoch.id())) {
          throw new BadJwtException("session expired");
        }
      }
      return jwt;
    };
  }
}
