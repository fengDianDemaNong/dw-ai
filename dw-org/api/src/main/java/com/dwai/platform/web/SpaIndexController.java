package com.dwai.platform.web;

import com.dwai.platform.DwaiProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** 安装包把控制台放在 web/。组织平台只有 multi，根路径进登录。 */
@Controller
public class SpaIndexController {
  private final DwaiProperties props;

  public SpaIndexController(DwaiProperties props) {
    this.props = props;
  }

  @GetMapping("/")
  public ResponseEntity<Void> home() {
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/org/login")).build();
  }

  @GetMapping({
      "/login",
      "/org",
      "/org/login",
      "/org/select-tenant",
      "/select-tenant",
      "/workbench",
      "/platform",
      "/no-project",
      "/forbidden"
  })
  public ResponseEntity<Resource> spaRoot() {
    return index();
  }

  private ResponseEntity<Resource> index() {
    String dir = props.getWeb().getStaticDir();
    if (!StringUtils.hasText(dir)) {
      return ResponseEntity.notFound().build();
    }
    Path file = Path.of(dir).toAbsolutePath().normalize().resolve("index.html");
    if (!Files.isRegularFile(file)) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .body(new FileSystemResource(file));
  }
}
