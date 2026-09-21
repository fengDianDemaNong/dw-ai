package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.TenantAiPromptEntity;
import com.dwai.platform.meta.mapper.TenantAiPromptMapper;
import com.dwai.platform.meta.support.AiCaps;
import com.dwai.platform.meta.support.AiPromptDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AiPromptService {
  private final AccessService access;
  private final TenantAiPromptMapper prompts;

  public AiPromptService(AccessService access, TenantAiPromptMapper prompts) {
    this.access = access;
    this.prompts = prompts;
  }

  public ApiModels.AiPromptsDto get(String tenantId) {
    access.requireTenantAdmin();
    requireTenant(tenantId);
    Map<String, String> defaults = AiPromptDefaults.all();
    Map<String, String> overrides = new LinkedHashMap<>();
    Map<String, String> effective = new LinkedHashMap<>();
    for (TenantAiPromptEntity e : prompts.selectList(Wrappers.<TenantAiPromptEntity>lambdaQuery()
        .eq(TenantAiPromptEntity::getTenantId, tenantId))) {
      if (e.getSlot() != null && e.getBody() != null) overrides.put(e.getSlot(), e.getBody());
    }
    for (Map.Entry<String, String> d : defaults.entrySet()) {
      effective.put(d.getKey(), overrides.getOrDefault(d.getKey(), d.getValue()));
    }
    return new ApiModels.AiPromptsDto(defaults, overrides, effective);
  }

  @Transactional
  public ApiModels.AiPromptsDto put(String tenantId, ApiModels.AiPromptsPutReq req) {
    access.requireTenantAdmin();
    requireTenant(tenantId);
    Map<String, String> overrides = req == null || req.overrides() == null ? Map.of() : req.overrides();
    Map<String, String> defaults = AiPromptDefaults.all();
    for (Map.Entry<String, String> e : overrides.entrySet()) {
      String slot = e.getKey();
      if (!AiCaps.SLOTS.contains(slot)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知提示词槽位: " + slot);
      }
      String body = e.getValue() == null ? "" : e.getValue();
      String def = defaults.get(slot);
      if (body.isBlank() || body.equals(def)) {
        prompts.delete(Wrappers.<TenantAiPromptEntity>lambdaQuery()
            .eq(TenantAiPromptEntity::getTenantId, tenantId)
            .eq(TenantAiPromptEntity::getSlot, slot));
        continue;
      }
      TenantAiPromptEntity exist = prompts.selectOne(Wrappers.<TenantAiPromptEntity>lambdaQuery()
          .eq(TenantAiPromptEntity::getTenantId, tenantId)
          .eq(TenantAiPromptEntity::getSlot, slot));
      if (exist == null) {
        TenantAiPromptEntity row = new TenantAiPromptEntity();
        row.setTenantId(tenantId);
        row.setSlot(slot);
        row.setBody(body);
        row.setUpdatedAt(OffsetDateTime.now());
        prompts.insert(row);
      } else {
        exist.setBody(body);
        exist.setUpdatedAt(OffsetDateTime.now());
        prompts.update(exist, Wrappers.<TenantAiPromptEntity>lambdaQuery()
            .eq(TenantAiPromptEntity::getTenantId, tenantId)
            .eq(TenantAiPromptEntity::getSlot, slot));
      }
    }
    return get(tenantId);
  }

  public String effective(String tenantId, String slot) {
    if (!AiCaps.SLOTS.contains(slot)) return AiPromptDefaults.defaultOf(AiCaps.SLOT_SPEC);
    TenantAiPromptEntity e = prompts.selectOne(Wrappers.<TenantAiPromptEntity>lambdaQuery()
        .eq(TenantAiPromptEntity::getTenantId, tenantId)
        .eq(TenantAiPromptEntity::getSlot, slot));
    if (e != null && e.getBody() != null && !e.getBody().isBlank()) return e.getBody();
    String def = AiPromptDefaults.defaultOf(slot);
    return def == null ? "" : def;
  }

  private void requireTenant(String tenantId) {
    if (!access.requireTenant().getId().equals(tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "组织不匹配");
    }
  }
}
