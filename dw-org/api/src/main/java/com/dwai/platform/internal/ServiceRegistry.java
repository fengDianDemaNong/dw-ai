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

/**
 * 每个产品只留最新一条登记（库 + 内存）。
 *
 * <p>改前它是一张<b>探活表</b>：模块每 30s 心跳覆盖写。那带来一个真实的坑 ——
 * 人工登记的地址 30 秒内就被心跳抹掉（见 {@code docs/tech/service-registry-address-overwrite.md}）。
 * 门户集成把项目同步从 push 改成 pull 之后，组织不再需要知道模块的后端地址，
 * 心跳随之删除，这一层退化成<b>纯配置</b>：只有人工登记会写它。
 */
@Component
@Order(0)
public class ServiceRegistry implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(ServiceRegistry.class);

  public record Entry(String product, String version, String baseUrl, String frontendUrl, Instant seenAt) {}

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

  /**
   * 登记一个产品。
   *
   * @param baseUrl    已退役的模块后端地址。调用方给空值时<b>保留旧值</b>，不清掉 ——
   *                   项目同步改 pull 是分步落地的，过渡期里 push 仍需要它。
   * @param frontendUrl 产品页面的前端地址，门户嵌入用
   */
  public Entry put(String product, String version, String baseUrl, String frontendUrl) {
    String code = product.trim();
    Entry prev = byProduct.get(code);
    String effectiveBase = (baseUrl == null || baseUrl.isBlank())
        ? (prev == null ? "" : prev.baseUrl())
        : baseUrl;
    Entry e = new Entry(
        code,
        version == null ? "" : version,
        effectiveBase,
        frontendUrl == null ? "" : frontendUrl,
        Instant.now());
    // 先落库再更新内存：落库失败时不能留下「内存说有、库里没有」的不一致
    persist(e);
    byProduct.put(code, e);
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

  /**
   * 落库；失败直接抛出。
   *
   * <p>这里以前是 {@code catch (Exception) { log.warn(...) }} —— 手工登记写库失败时
   * 前端照样看到「已登记」，重启后配置没了的形态。那次事故的复盘全文见
   * {@code docs/tech/service-registry-address-overwrite.md}。现在让异常上抛，
   * 由 controller 变成 4xx/5xx 让用户看见。
   */
  private void persist(Entry e) {
    ServiceRegistryEntity row = mapper.selectById(e.product());
    boolean insert = row == null;
    if (insert) row = new ServiceRegistryEntity();
    row.setProduct(e.product());
    row.setVersion(e.version());
    row.setBaseUrl(e.baseUrl());
    row.setSeenAt(OffsetDateTime.ofInstant(e.seenAt(), ZoneOffset.UTC));
    row.setFrontendUrl(e.frontendUrl());
    if (insert) mapper.insert(row);
    else mapper.updateById(row);
  }

  private static Entry fromRow(ServiceRegistryEntity row) {
    if (row == null || row.getProduct() == null || row.getProduct().isBlank()) return null;
    Instant seen = row.getSeenAt() == null ? Instant.EPOCH : row.getSeenAt().toInstant();
    return new Entry(
        row.getProduct(),
        row.getVersion() == null ? "" : row.getVersion(),
        row.getBaseUrl() == null ? "" : row.getBaseUrl(),
        row.getFrontendUrl() == null ? "" : row.getFrontendUrl(),
        seen);
  }
}
