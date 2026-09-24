package com.dwai.lineage.conf;

import com.dwai.lineage.tenant.TenantInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 与租户拦截器配置。
 *
 * <p>生产环境前端与后端应通过 nginx 反向代理同源访问（见 ui/nginx.conf），
 * 此时不需要跨域。仅在本地开发或前后端分离部署时才放开，且必须配置白名单。
 */
@Configuration
@EnableConfigurationProperties(LineageProperties.class)
public class WebConfig implements WebMvcConfigurer {

    /**
     * 允许跨域的来源，逗号分隔。默认只放开本地开发端口。
     * 设为 {@code *} 会退回到「允许所有来源」，仅建议在内网调试时使用。
     */
    @Value("${cors.allowed-origins:http://localhost:5173,http://localhost:3000}")
    private String allowedOrigins;

    private final TenantInterceptor tenantInterceptor;

    public WebConfig(TenantInterceptor tenantInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
    }

    /**
     * 租户上下文只对 {@code /api/**} 生效。
     *
     * <p>静态资源不需要租户，挂上去只会平白多一次 ThreadLocal 读写。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.split(",");
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
        registry.addMapping("/internal/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
