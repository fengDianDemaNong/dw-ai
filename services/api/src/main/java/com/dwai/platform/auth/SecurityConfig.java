package com.dwai.platform.auth;

import com.dwai.platform.DwaiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, TenantFilter tenantFilter) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(reg -> reg
            .requestMatchers(
                "/api/auth/config",
                "/api/auth/login",
                "/actuator/health",
                "/api/health")
            .permitAll()
            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
            .requestMatchers("/api/**").authenticated()
            .anyRequest().permitAll())
        .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
        .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  TenantFilter tenantFilter(
      DwaiProperties props,
      com.dwai.platform.meta.mapper.TenantMapper tenants,
      com.dwai.platform.meta.mapper.UserMapper users,
      com.dwai.platform.meta.mapper.UserTenantMapper userTenants,
      com.dwai.platform.meta.mapper.PlatformAccessMapper access,
      com.dwai.platform.meta.mapper.TenantGrantMapper grants) {
    return new TenantFilter(props, tenants, users, userTenants, access, grants);
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
  JwtDecoder jwtDecoder(DwaiProperties props) {
    DwaiProperties.Security sec = props.getSecurity();
    if (sec.isOidc() && sec.getCasdoor().configured()) {
      // Casdoor 默认 RS256；启动时不拉 metadata，避免没装 Casdoor 就起不来
      return NimbusJwtDecoder.withJwkSetUri(sec.getCasdoor().resolvedJwkSetUri())
          .jwsAlgorithm(SignatureAlgorithm.RS256)
          .build();
    }
    byte[] secret = sec.getJwtSecret().getBytes(StandardCharsets.UTF_8);
    SecretKeySpec key = new SecretKeySpec(secret, "HmacSHA256");
    return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
  }
}
