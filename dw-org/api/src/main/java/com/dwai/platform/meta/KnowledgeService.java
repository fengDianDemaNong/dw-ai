package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.auth.TenantContext;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.TenantKnowledgeArticleEntity;
import com.dwai.platform.meta.mapper.TenantKnowledgeArticleMapper;
import com.dwai.platform.meta.support.AiCaps;
import com.dwai.platform.meta.support.Jsons;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeService {
  private static final ObjectMapper M = new ObjectMapper();
  private final AccessService access;
  private final TenantKnowledgeArticleMapper articles;
  private volatile List<Map<String, Object>> manuals;

  public KnowledgeService(AccessService access, TenantKnowledgeArticleMapper articles) {
    this.access = access;
    this.articles = articles;
  }

  public List<Map<String, Object>> manuals(String engine) {
    access.requireUser();
    List<Map<String, Object>> all = loadManuals();
    if (engine == null || engine.isBlank()) return all;
    String e = engine.trim().toLowerCase();
    return all.stream().filter(m -> e.equals(String.valueOf(m.get("engine")))).toList();
  }

  public List<ApiModels.KnowledgeArticleDto> listImported(String tenantId) {
    access.requireTenant();
    if (!access.requireTenant().getId().equals(tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "组织不匹配");
    }
    return articles.selectList(Wrappers.<TenantKnowledgeArticleEntity>lambdaQuery()
            .eq(TenantKnowledgeArticleEntity::getTenantId, tenantId))
        .stream()
        .map(this::toDto)
        .toList();
  }

  @Transactional
  public Map<String, Object> importArticles(String tenantId, ApiModels.KnowledgeImportReq req) {
    access.requireTenantAdmin();
    if (!access.requireTenant().getId().equals(tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "组织不匹配");
    }
    if (req == null || req.text() == null || req.text().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传知识库文件");
    }
    KnowledgeImportParser.Result parsed = KnowledgeImportParser.parse(req.filename(), req.text());
    boolean replace = "replace-engine".equalsIgnoreCase(req.mode());
    if (replace) {
      for (String engine : parsed.articles().stream().map(KnowledgeImportParser.Article::engine).distinct().toList()) {
        articles.delete(Wrappers.<TenantKnowledgeArticleEntity>lambdaQuery()
            .eq(TenantKnowledgeArticleEntity::getTenantId, tenantId)
            .eq(TenantKnowledgeArticleEntity::getEngine, engine));
      }
    }
    String user = TenantContext.user();
    OffsetDateTime now = OffsetDateTime.now();
    List<ApiModels.KnowledgeArticleDto> saved = new ArrayList<>();
    for (KnowledgeImportParser.Article a : parsed.articles()) {
      TenantKnowledgeArticleEntity exist = articles.selectOne(Wrappers.<TenantKnowledgeArticleEntity>lambdaQuery()
          .eq(TenantKnowledgeArticleEntity::getTenantId, tenantId)
          .eq(TenantKnowledgeArticleEntity::getEngine, a.engine())
          .eq(TenantKnowledgeArticleEntity::getArticleId, a.id()));
      TenantKnowledgeArticleEntity e = exist == null ? new TenantKnowledgeArticleEntity() : exist;
      e.setTenantId(tenantId);
      e.setEngine(a.engine());
      e.setArticleId(a.id());
      e.setTitle(a.title());
      e.setSummary(a.summary());
      e.setBody(a.body());
      e.setSourceUrl(a.sourceUrl());
      e.setSourceLabel(a.sourceLabel());
      e.setSections(Jsons.toJson(a.sections()));
      e.setNotes(Jsons.toJson(a.notes()));
      e.setImportedAt(now);
      e.setImportedBy(user);
      if (exist == null) articles.insert(e);
      else {
        articles.update(e, Wrappers.<TenantKnowledgeArticleEntity>lambdaQuery()
            .eq(TenantKnowledgeArticleEntity::getTenantId, tenantId)
            .eq(TenantKnowledgeArticleEntity::getEngine, a.engine())
            .eq(TenantKnowledgeArticleEntity::getArticleId, a.id()));
      }
      saved.add(toDto(e));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("articles", saved);
    out.put("warnings", parsed.warnings());
    out.put("format", parsed.format());
    return out;
  }

  @Transactional
  public void deleteImported(String tenantId, String engine, String articleId) {
    access.requireTenantAdmin();
    if (!access.requireTenant().getId().equals(tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "组织不匹配");
    }
    if (!AiCaps.ENGINES.contains(engine)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知引擎");
    }
    long n = articles.delete(Wrappers.<TenantKnowledgeArticleEntity>lambdaQuery()
        .eq(TenantKnowledgeArticleEntity::getTenantId, tenantId)
        .eq(TenantKnowledgeArticleEntity::getEngine, engine)
        .eq(TenantKnowledgeArticleEntity::getArticleId, articleId));
    if (n < 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "导入篇不存在");
  }

  private ApiModels.KnowledgeArticleDto toDto(TenantKnowledgeArticleEntity e) {
    return new ApiModels.KnowledgeArticleDto(
        e.getArticleId(),
        e.getTenantId(),
        e.getEngine(),
        e.getTitle(),
        e.getSummary(),
        e.getBody(),
        e.getSourceUrl(),
        e.getSourceLabel(),
        Jsons.maps(e.getSections()),
        Jsons.strings(e.getNotes()),
        e.getImportedAt() == null ? null : e.getImportedAt().toString(),
        e.getImportedBy());
  }

  private List<Map<String, Object>> loadManuals() {
    if (manuals != null) return manuals;
    synchronized (this) {
      if (manuals != null) return manuals;
      try (InputStream in = new ClassPathResource("knowledge/manuals.json").getInputStream()) {
        manuals = M.readValue(in, new TypeReference<>() {});
      } catch (Exception e) {
        manuals = List.of();
      }
      return manuals;
    }
  }
}
