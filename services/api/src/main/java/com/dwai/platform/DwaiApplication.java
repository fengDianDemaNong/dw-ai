package com.dwai.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.dwai.platform.meta.mapper")
public class DwaiApplication {
  public static void main(String[] args) {
    SpringApplication.run(DwaiApplication.class, args);
  }
}
