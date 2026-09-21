package com.dwai.platform.internal;

import com.dwai.platform.meta.entity.ServiceRegistryEntity;
import com.dwai.platform.meta.mapper.ServiceRegistryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/** 每个产品只留最新一条登记（库 + 内存）。心跳覆盖写，不记流水。 */
@Component
@Order(0)
public class ServiceRegistry implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(ServiceRegistry.class);

  public record Entry(String product, String version, String baseUrl, Instant seenAt) {}

  private final ConcurrentHashMap<String, Entry> byProduct = new ConcurrentHashMap<>();
  private final ServiceRegistryMapper mapper;

  public ServiceRegistry(ServiceRegistryMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      for (ServiceRegistryEntity row : mapper.selectList(null)) {
        Entry e = fromRow(row);
        if (e != null) byProduct.put(e.product(), e);
      }
    } catch (Exception e) {
      log.warn("加载服务登记失败: {}", e.getMessage());
    }
  }

  public Entry put(String product, String version, String baseUrl) {
    String code = product.trim();
    Entry e = new Entry(code, version == null ? "" : version, baseUrl, Instant.now());
    byProduct.put(code, e);
    persist(e);
    return e;
  }

  public Collection<Entry> all() {
    return byProduct.values();
  }

  public Entry get(String product) {
    return product == null ? null : byProduct.get(product);
  }

  public void remove(String product) {
    if (product == null) return;
    byProduct.remove(product);
    try {
      mapper.deleteById(product);
    } catch (Exception e) {
      log.warn("删除服务登记 {} 失败: {}", product, e.getMessage());
    }
  }

  private void persist(Entry e) {
    try {
      ServiceRegistryEntity row = mapper.selectById(e.product());
      boolean insert = row == null;
      if (insert) row = new ServiceRegistryEntity();
      row.setProduct(e.product());
      row.setVersion(e.version());
      row.setBaseUrl(e.baseUrl());
      row.setSeenAt(OffsetDateTime.ofInstant(e.seenAt(), ZoneOffset.UTC));
      if (insert) mapper.insert(row);
      else mapper.updateById(row);
    } catch (Exception ex) {
      log.warn("写入服务登记 {} 失败: {}", e.product(), ex.getMessage());
    }
  }

  private static Entry fromRow(ServiceRegistryEntity row) {
    if (row == null || row.getProduct() == null || row.getProduct().isBlank()) return null;
    Instant seen = row.getSeenAt() == null ? Instant.EPOCH : row.getSeenAt().toInstant();
    return new Entry(row.getProduct(), row.getVersion() == null ? "" : row.getVersion(), row.getBaseUrl(), seen);
  }
}
