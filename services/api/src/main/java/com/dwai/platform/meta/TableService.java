package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.ModelingDraftEntity;
import com.dwai.platform.meta.entity.TableColumnEntity;
import com.dwai.platform.meta.entity.WarehouseTableEntity;
import com.dwai.platform.meta.mapper.ModelingDraftMapper;
import com.dwai.platform.meta.mapper.TableColumnMapper;
import com.dwai.platform.meta.mapper.WarehouseTableMapper;
import com.dwai.platform.meta.support.Jsons;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TableService {
  private final AccessService access;
  private final WarehouseTableMapper tables;
  private final TableColumnMapper columns;
  private final ModelingDraftMapper drafts;
  private final TableVersionService versions;

  public TableService(
      AccessService access,
      WarehouseTableMapper tables,
      TableColumnMapper columns,
      ModelingDraftMapper drafts,
      @org.springframework.context.annotation.Lazy TableVersionService versions) {
    this.access = access;
    this.tables = tables;
    this.columns = columns;
    this.drafts = drafts;
    this.versions = versions;
  }

  public List<ApiModels.TableDto> listTables(String projectId) {
    access.requireMember(projectId, "model:read");
    return tables.selectList(Wrappers.<WarehouseTableEntity>lambdaQuery().eq(WarehouseTableEntity::getProjectId, projectId))
        .stream().map(this::toTable).toList();
  }

  public ApiModels.TableDto getTable(String projectId, String tableId) {
    access.requireMember(projectId, "model:read");
    WarehouseTableEntity t = tables.selectById(tableId);
    if (t == null || !projectId.equals(t.getProjectId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "表不存在");
    }
    return toTable(t);
  }

  @Transactional
  public ApiModels.TableDto saveTable(String projectId, ApiModels.TableDto in) {
    return saveTable(projectId, in, null);
  }

  @Transactional
  public ApiModels.TableDto saveTable(String projectId, ApiModels.TableDto in, String versionNote) {
    access.requireMember(projectId, "model:write");
    if (in.name() == null || in.name().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "表名必填");
    }
    WarehouseTableEntity exist = in.id() == null ? null : tables.selectById(in.id());
    long clash = tables.selectCount(Wrappers.<WarehouseTableEntity>lambdaQuery()
        .eq(WarehouseTableEntity::getProjectId, projectId)
        .eq(WarehouseTableEntity::getName, in.name())
        .ne(exist != null, WarehouseTableEntity::getId, exist == null ? "" : exist.getId()));
    if (clash > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "项目内表名必须唯一");
    WarehouseTableEntity e = exist == null ? new WarehouseTableEntity() : exist;
    if (exist == null) e.setId(in.id() == null || in.id().isBlank() ? "tbl-" + System.currentTimeMillis() : in.id());
    e.setProjectId(projectId);
    e.setLayer(in.layer());
    e.setName(in.name());
    e.setComment(in.comment());
    e.setDomain(in.domain());
    e.setSourceSystem(in.sourceSystem());
    e.setGrain(in.grain());
    e.setPeriod(in.period());
    e.setPartitionCol(in.partition());
    e.setStoredAs(in.storedAs());
    e.setStatus(in.status() == null ? "draft" : in.status());
    e.setCreatedFrom(in.createdFrom());
    e.setGrade(in.grade());
    if (exist == null) tables.insert(e); else tables.updateById(e);
    columns.delete(Wrappers.<TableColumnEntity>lambdaQuery().eq(TableColumnEntity::getTableId, e.getId()));
    List<ApiModels.ColumnDto> cols = in.columns() == null ? List.of() : in.columns();
    int pos = 0;
    for (ApiModels.ColumnDto c : cols) {
      TableColumnEntity col = new TableColumnEntity();
      col.setTableId(e.getId());
      col.setName(c.name());
      col.setType(c.type());
      col.setComment(c.comment());
      col.setNullable(c.nullable());
      col.setDefaultValue(c.defaultValue());
      col.setSensitive(c.sensitive());
      col.setGrade(c.grade());
      col.setEnumValues(c.enumValues() == null ? null : Jsons.toJson(c.enumValues()));
      col.setPos(pos++);
      columns.insert(col);
    }
    ApiModels.TableDto saved = toTable(e);
    versions.recordIfChanged(projectId, saved, versionNote);
    return saved;
  }

  @Transactional
  public void deleteTable(String projectId, String tableId) {
    access.requireMember(projectId, "model:write");
    WarehouseTableEntity t = tables.selectById(tableId);
    if (t == null || !projectId.equals(t.getProjectId())) return;
    columns.delete(Wrappers.<TableColumnEntity>lambdaQuery().eq(TableColumnEntity::getTableId, tableId));
    tables.deleteById(tableId);
  }

  @Transactional
  public void syncTables(String projectId, List<ApiModels.TableDto> body) {
    access.requireMember(projectId, "model:write");
    if (body == null) return;
    for (ApiModels.TableDto t : body) saveTable(projectId, t);
  }

  public List<ApiModels.DraftDto> listDrafts(String projectId) {
    access.requireMember(projectId, "model:read");
    return drafts.selectList(Wrappers.<ModelingDraftEntity>lambdaQuery()
            .eq(ModelingDraftEntity::getProjectId, projectId)
            .orderByDesc(ModelingDraftEntity::getCreatedAt))
        .stream().map(this::toDraft).toList();
  }

  @Transactional
  public ApiModels.DraftDto saveDraft(String projectId, ApiModels.DraftDto in) {
    access.requireMember(projectId, "model:write");
    ModelingDraftEntity exist = in.id() == null ? null : drafts.selectById(in.id());
    ModelingDraftEntity e = exist == null ? new ModelingDraftEntity() : exist;
    if (exist == null) {
      e.setId(in.id() == null || in.id().isBlank() ? "draft-" + System.currentTimeMillis() : in.id());
      e.setCreatedAt(OffsetDateTime.now());
    }
    e.setProjectId(projectId);
    e.setSourceTableId(in.sourceTableId());
    e.setTargetLayer(in.targetLayer());
    e.setDomainCode(in.domainCode());
    e.setDomainConfidence(in.domainConfidence() == null ? null : BigDecimal.valueOf(in.domainConfidence()));
    e.setGrain(in.grain());
    e.setPrimaryKeys(Jsons.toJson(in.primaryKeys() == null ? List.of() : in.primaryKeys()));
    e.setFieldTags(Jsons.toJson(in.fieldTags() == null ? List.of() : in.fieldTags()));
    e.setDdl(in.ddl());
    e.setEtlSql(in.etlSql());
    e.setQualityRules(Jsons.toJson(in.qualityRules() == null ? List.of() : in.qualityRules()));
    e.setSpecIssues(Jsons.toJson(in.specIssues() == null ? List.of() : in.specIssues()));
    e.setStatus(in.status() == null ? "pending_review" : in.status());
    if (exist == null) drafts.insert(e); else drafts.updateById(e);
    return toDraft(e);
  }

  @Transactional
  public void syncDrafts(String projectId, List<ApiModels.DraftDto> body) {
    access.requireMember(projectId, "model:write");
    if (body == null) return;
    for (ApiModels.DraftDto d : body) saveDraft(projectId, d);
  }

  private ApiModels.TableDto toTable(WarehouseTableEntity t) {
    List<TableColumnEntity> cols = columns.selectList(Wrappers.<TableColumnEntity>lambdaQuery()
        .eq(TableColumnEntity::getTableId, t.getId())
        .orderByAsc(TableColumnEntity::getPos));
    List<ApiModels.ColumnDto> out = new ArrayList<>();
    for (TableColumnEntity c : cols) {
      out.add(new ApiModels.ColumnDto(
          c.getName(), c.getType(), c.getComment(), c.getNullable(), c.getDefaultValue(),
          c.getSensitive(), c.getGrade(), Jsons.strings(c.getEnumValues())));
    }
    return new ApiModels.TableDto(
        t.getId(), t.getProjectId(), t.getLayer(), t.getName(), t.getComment(), t.getDomain(),
        t.getSourceSystem(), t.getGrain(), t.getPeriod(), out, t.getPartitionCol(), t.getStoredAs(),
        t.getStatus(), t.getCreatedFrom(), t.getGrade());
  }

  @SuppressWarnings("unchecked")
  private ApiModels.DraftDto toDraft(ModelingDraftEntity e) {
    return new ApiModels.DraftDto(
        e.getId(), e.getProjectId(), e.getSourceTableId(), e.getTargetLayer(), e.getDomainCode(),
        e.getDomainConfidence() == null ? null : e.getDomainConfidence().doubleValue(),
        e.getGrain(), Jsons.strings(e.getPrimaryKeys()),
        (List<Map<String, Object>>) (List<?>) Jsons.maps(e.getFieldTags()),
        e.getDdl(), e.getEtlSql(),
        Jsons.maps(e.getQualityRules()), Jsons.maps(e.getSpecIssues()),
        e.getStatus(), e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
  }
}
