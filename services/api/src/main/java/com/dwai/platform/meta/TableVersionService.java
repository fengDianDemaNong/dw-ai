package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.auth.TenantContext;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.TableVersionEntity;
import com.dwai.platform.meta.entity.WarehouseTableEntity;
import com.dwai.platform.meta.mapper.TableVersionMapper;
import com.dwai.platform.meta.mapper.WarehouseTableMapper;
import com.dwai.platform.meta.support.Jsons;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TableVersionService {
  private static final ObjectMapper M = new ObjectMapper();
  private final AccessService access;
  private final TableVersionMapper versions;
  private final WarehouseTableMapper tables;
  private final TableService tableService;

  public TableVersionService(
      AccessService access,
      TableVersionMapper versions,
      WarehouseTableMapper tables,
      @Lazy TableService tableService) {
    this.access = access;
    this.versions = versions;
    this.tables = tables;
    this.tableService = tableService;
  }

  public List<ApiModels.TableVersionDto> list(String projectId, String tableId) {
    access.requireMember(projectId, "model:read");
    ensureInitial(projectId, tableId);
    return versions.selectList(Wrappers.<TableVersionEntity>lambdaQuery()
            .eq(TableVersionEntity::getTableId, tableId)
            .orderByDesc(TableVersionEntity::getVersion))
        .stream()
        .map(e -> toDto(e, projectId))
        .toList();
  }

  public ApiModels.TableVersionDto get(String projectId, String tableId, String versionId) {
    access.requireMember(projectId, "model:read");
    TableVersionEntity e = versions.selectById(versionId);
    if (e == null || !tableId.equals(e.getTableId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
    }
    return toDto(e, projectId);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> diff(String projectId, String tableId, String fromId, String toId) {
    access.requireMember(projectId, "model:read");
    ApiModels.TableVersionDto from = get(projectId, tableId, fromId);
    ApiModels.TableVersionDto to = get(projectId, tableId, toId);
    Map<String, Object> a = from.snapshot();
    Map<String, Object> b = to.snapshot();
    List<Map<String, String>> meta = new ArrayList<>();
    for (String k : List.of("name", "comment", "domain", "grain", "period", "partition", "status", "grade")) {
      String av = String.valueOf(a.getOrDefault(k, ""));
      String bv = String.valueOf(b.getOrDefault(k, ""));
      if (!av.equals(bv)) meta.add(Map.of("field", k, "from", av.isBlank() ? "—" : av, "to", bv.isBlank() ? "—" : bv));
    }
    List<Map<String, Object>> ac = (List<Map<String, Object>>) a.getOrDefault("columns", List.of());
    List<Map<String, Object>> bc = (List<Map<String, Object>>) b.getOrDefault("columns", List.of());
    Map<String, Map<String, Object>> am = new LinkedHashMap<>();
    Map<String, Map<String, Object>> bm = new LinkedHashMap<>();
    for (Map<String, Object> c : ac) am.put(String.valueOf(c.get("name")), c);
    for (Map<String, Object> c : bc) bm.put(String.valueOf(c.get("name")), c);
    List<Map<String, Object>> added = new ArrayList<>();
    List<Map<String, Object>> removed = new ArrayList<>();
    List<Map<String, Object>> changed = new ArrayList<>();
    for (var e : bm.entrySet()) {
      if (!am.containsKey(e.getKey())) added.add(e.getValue());
      else if (!normalizeCol(am.get(e.getKey())).equals(normalizeCol(e.getValue()))) {
        changed.add(Map.of("name", e.getKey(), "from", am.get(e.getKey()), "to", e.getValue()));
      }
    }
    for (var e : am.entrySet()) {
      if (!bm.containsKey(e.getKey())) removed.add(e.getValue());
    }
    return Map.of("meta", meta, "added", added, "removed", removed, "changed", changed);
  }

  @Transactional
  public ApiModels.TableDto restore(String projectId, String tableId, String versionId) {
    access.requireMember(projectId, "model:write");
    TableVersionEntity e = versions.selectById(versionId);
    if (e == null || !tableId.equals(e.getTableId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
    }
    ApiModels.TableDto cur = tableService.getTable(projectId, tableId);
    Map<String, Object> snap = readSnap(e.getSnapshot());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> cols = (List<Map<String, Object>>) snap.getOrDefault("columns", List.of());
    List<ApiModels.ColumnDto> columns = new ArrayList<>();
    for (Map<String, Object> c : cols) {
      columns.add(new ApiModels.ColumnDto(
          str(c.get("name")),
          str(c.get("type")),
          str(c.get("comment")),
          c.get("nullable") instanceof Boolean b ? b : null,
          str(c.get("defaultValue")),
          c.get("sensitive") instanceof Boolean s ? s : null,
          str(c.get("grade")),
          c.get("enumValues") instanceof List<?> ev ? ev.stream().map(String::valueOf).toList() : List.of()));
    }
    ApiModels.TableDto body = new ApiModels.TableDto(
        tableId,
        projectId,
        cur.layer(),
        str(snap.get("name")),
        str(snap.get("comment")),
        str(snap.get("domain")),
        cur.sourceSystem(),
        str(snap.get("grain")),
        str(snap.get("period")),
        columns,
        str(snap.get("partition")),
        cur.storedAs(),
        str(snap.getOrDefault("status", cur.status())),
        cur.createdFrom(),
        str(snap.get("grade")));
    ApiModels.TableDto saved = tableService.saveTable(projectId, body, "从 v" + e.getVersion() + " 恢复");
    return saved;
  }

  @Transactional
  public void recordIfChanged(String projectId, ApiModels.TableDto table, String note) {
    if (table == null || table.id() == null) return;
    String snap = Jsons.toJson(snapshotOf(table));
    TableVersionEntity latest = versions.selectOne(Wrappers.<TableVersionEntity>lambdaQuery()
        .eq(TableVersionEntity::getTableId, table.id())
        .orderByDesc(TableVersionEntity::getVersion)
        .last("LIMIT 1"));
    if (latest != null && sameSnap(latest.getSnapshot(), snap)) return;
    int next = latest == null ? 1 : latest.getVersion() + 1;
    TableVersionEntity e = new TableVersionEntity();
    e.setId("tv-" + table.id() + "-" + next);
    e.setTableId(table.id());
    e.setVersion(next);
    e.setNote(note == null || note.isBlank() ? (next == 1 ? "初始版本" : "结构变更") : note);
    e.setActorUserId(TenantContext.user());
    e.setCreatedAt(OffsetDateTime.now());
    e.setSnapshot(snap);
    versions.insert(e);
    WarehouseTableEntity t = tables.selectById(table.id());
    if (t != null) {
      t.setCurrentVersion(next);
      tables.updateById(t);
    }
  }

  private void ensureInitial(String projectId, String tableId) {
    long n = versions.selectCount(Wrappers.<TableVersionEntity>lambdaQuery().eq(TableVersionEntity::getTableId, tableId));
    if (n > 0) return;
    ApiModels.TableDto t = tableService.getTable(projectId, tableId);
    recordIfChanged(projectId, t, "初始版本");
  }

  private ApiModels.TableVersionDto toDto(TableVersionEntity e, String projectId) {
    return new ApiModels.TableVersionDto(
        e.getId(),
        e.getTableId(),
        projectId,
        e.getVersion() == null ? 0 : e.getVersion(),
        e.getNote(),
        e.getActorUserId(),
        e.getCreatedAt() == null ? null : e.getCreatedAt().toString(),
        readSnap(e.getSnapshot()));
  }

  private static Map<String, Object> snapshotOf(ApiModels.TableDto t) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", t.name());
    m.put("comment", t.comment() == null ? "" : t.comment());
    m.put("domain", t.domain() == null ? "" : t.domain());
    m.put("grain", t.grain() == null ? "" : t.grain());
    m.put("period", t.period() == null ? "" : t.period());
    m.put("partition", t.partition() == null ? "" : t.partition());
    m.put("status", t.status());
    m.put("grade", t.grade() == null ? "" : t.grade());
    List<Map<String, Object>> cols = new ArrayList<>();
    if (t.columns() != null) {
      for (ApiModels.ColumnDto c : t.columns()) {
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("name", c.name());
        col.put("type", c.type());
        col.put("comment", c.comment() == null ? "" : c.comment());
        col.put("nullable", c.nullable() == null || c.nullable());
        col.put("defaultValue", c.defaultValue() == null ? "" : c.defaultValue());
        col.put("sensitive", Boolean.TRUE.equals(c.sensitive()));
        col.put("grade", c.grade() == null ? "" : c.grade());
        col.put("enumValues", c.enumValues() == null ? List.of() : c.enumValues());
        cols.add(col);
      }
    }
    m.put("columns", cols);
    return m;
  }

  private static boolean sameSnap(String a, String b) {
    return normalizeJson(a).equals(normalizeJson(b));
  }

  private static String normalizeJson(String raw) {
    try {
      return M.writeValueAsString(M.readValue(raw, new TypeReference<Map<String, Object>>() {}));
    } catch (Exception e) {
      return raw == null ? "" : raw;
    }
  }

  private static Map<String, Object> normalizeCol(Map<String, Object> c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", String.valueOf(c.getOrDefault("name", "")));
    m.put("type", String.valueOf(c.getOrDefault("type", "")));
    m.put("comment", String.valueOf(c.getOrDefault("comment", "")));
    return m;
  }

  private static Map<String, Object> readSnap(String raw) {
    try {
      return M.readValue(raw == null ? "{}" : raw, new TypeReference<>() {});
    } catch (Exception e) {
      return Map.of();
    }
  }

  private static String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }
}
