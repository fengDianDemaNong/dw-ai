package com.dwai.platform.web;

import com.dwai.platform.DwaiProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.lang.NonNull;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final DwaiProperties props;

  public WebConfig(DwaiProperties props) {
    this.props = props;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    String raw = props.getWeb().getCorsOrigins();
    String[] origins = Arrays.stream((raw == null ? "" : raw).split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toArray(String[]::new);
    var mapping = registry.addMapping("/api/**")
        .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
        .allowedHeaders("*")
        .exposedHeaders("*");
    if (origins.length == 1 && "*".equals(origins[0])) {
      mapping.allowedOriginPatterns("*");
    } else if (origins.length > 0) {
      mapping.allowedOrigins(origins);
    }
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String dir = props.getWeb().getStaticDir();
    if (!StringUtils.hasText(dir)) {
      return;
    }
    Path root = Path.of(dir).toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      return;
    }
    String location = root.toUri().toString();
    if (!location.endsWith("/")) {
      location += "/";
    }
    registry.setOrder(Ordered.LOWEST_PRECEDENCE);
    registry.addResourceHandler("/**")
        .addResourceLocations(location)
        .resourceChain(true)
        .addResolver(new PathResourceResolver() {
          @Override
          protected Resource getResource(@NonNull String resourcePath, @NonNull Resource location)
              throws IOException {
            if (resourcePath == null || resourcePath.isBlank() || resourcePath.endsWith("/")) {
              return null;
            }
            Resource resolved = super.getResource(resourcePath, location);
            if (resolved != null && resolved.exists() && resolved.isReadable() && isRegularFile(resolved)) {
              return resolved;
            }
            if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
              return null;
            }
            return location.createRelative("index.html");
          }
        });
  }

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addRedirectViewController("/", "/login");
  }

  private static boolean isRegularFile(Resource resource) {
    try {
      return resource.isFile() && resource.getFile().isFile();
    } catch (IOException e) {
      return true;
    }
  }
}
