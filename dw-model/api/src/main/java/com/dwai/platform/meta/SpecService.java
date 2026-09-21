package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.DataGradeEntity;
import com.dwai.platform.meta.entity.DomainEntity;
import com.dwai.platform.meta.entity.LayerRuleEntity;
import com.dwai.platform.meta.entity.WarehouseTableEntity;
import com.dwai.platform.meta.entity.WordRootEntity;
import com.dwai.platform.meta.mapper.DataGradeMapper;
import com.dwai.platform.meta.mapper.DomainMapper;
import com.dwai.platform.meta.mapper.LayerRuleMapper;
import com.dwai.platform.meta.mapper.TableColumnMapper;
import com.dwai.platform.meta.mapper.WarehouseTableMapper;
import com.dwai.platform.meta.mapper.WordRootMapper;
import com.dwai.platform.meta.entity.TableColumnEntity;
import com.dwai.platform.meta.support.Jsons;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class SpecService {
  private final AccessService access;
  private final DomainMapper domains;
  private final LayerRuleMapper layers;
  private final DataGradeMapper grades;
  private final WordRootMapper roots;
  private final WarehouseTableMapper tables;
  private final TableColumnMapper columns;

  public SpecService(
      AccessService access,
      DomainMapper domains,
      LayerRuleMapper layers,
      DataGradeMapper grades,
      WordRootMapper roots,
      WarehouseTableMapper tables,
      TableColumnMapper columns) {
    this.access = access;
    this.domains = domains;
    this.layers = layers;
    this.grades = grades;
    this.roots = roots;
    this.tables = tables;
    this.columns = columns;
  }

  public List<ApiModels.DomainDto> listDomains(String projectId) {
    access.requireMember(projectId, "spec:read");
    return domains.selectList(Wrappers.<DomainEntity>lambdaQuery().eq(DomainEntity::getProjectId, projectId))
        .stream().map(this::toDomain).toList();
  }

  @Transactional
  public ApiModels.DomainDto saveDomain(String projectId, ApiModels.DomainDto in) {
    access.requireMember(projectId, "spec:write");
    String code = reqCode(in.code());
    DomainEntity exist = in.id() == null ? null : domains.selectById(in.id());
    long clash = domains.selectCount(Wrappers.<DomainEntity>lambdaQuery()
        .eq(DomainEntity::getProjectId, projectId).eq(DomainEntity::getCode, code)
        .ne(exist != null, DomainEntity::getId, exist == null ? "" : exist.getId()));
    if (clash > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "主题域编码必须项目内唯一");
    DomainEntity e = exist == null ? new DomainEntity() : exist;
    if (exist == null) e.setId(in.id() == null || in.id().isBlank() ? "d-" + System.currentTimeMillis() : in.id());
    e.setProjectId(projectId);
    e.setCode(code);
    e.setName(nz(in.name()));
    e.setDefinition(nz(in.definition()));
    e.setBizOwner(nz(in.bizOwner()));
    e.setTechOwner(nz(in.techOwner()));
    e.setDataOwner(nz(in.dataOwner()));
    e.setRelated(Jsons.toJson(in.related() == null ? List.of() : in.related()));
    e.setCoreEntities(Jsons.toJson(in.coreEntities() == null ? List.of() : in.coreEntities()));
    if (exist == null) domains.insert(e); else domains.updateById(e);
    return toDomain(e);
  }

  @Transactional
  public void deleteDomain(String projectId, String id) {
    access.requireMember(projectId, "spec:write");
    DomainEntity e = domains.selectById(id);
    if (e == null || !projectId.equals(e.getProjectId())) return;
    if (tables.selectCount(Wrappers.<WarehouseTableEntity>lambdaQuery()
        .eq(WarehouseTableEntity::getProjectId, projectId)
        .eq(WarehouseTableEntity::getDomain, e.getCode())) > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "主题域 " + e.getCode() + " 仍被表引用，无法删除");
    }
    domains.deleteById(id);
  }

  public List<ApiModels.LayerDto> listLayers(String projectId) {
    access.requireMember(projectId, "spec:read");
    return layers.selectList(
            Wrappers.<LayerRuleEntity>lambdaQuery().eq(LayerRuleEntity::getProjectId, projectId))
        .stream().map(this::toLayer).toList();
  }

  @Transactional
  public ApiModels.LayerDto saveLayer(String projectId, String prevLayer, ApiModels.LayerDto in) {
    access.requireMember(projectId, "spec:write");
    String layer = reqCode(in.layer());
    LayerRuleEntity exist = findLayer(projectId, prevLayer != null ? prevLayer : layer);
    if (exist == null && prevLayer != null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分层不存在");
    if (exist == null || !layer.equals(exist.getLayer())) {
      if (findLayer(projectId, layer) != null) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "分层 " + layer + " 已存在");
      }
    }
    LayerRuleEntity e = exist == null ? new LayerRuleEntity() : exist;
    String old = e.getLayer();
    e.setProjectId(projectId);
    e.setLayer(layer);
    e.setNaming(nz(in.naming()));
    e.setRetention(nz(in.retention()));
    e.setServe(in.serve() == null ? "forbid" : in.serve());
    e.setNote(nz(in.note()));
    e.setFieldFormat(in.fieldFormat());
    e.setTimeFormat(in.timeFormat());
    e.setMasking(in.masking());
    e.setMaskingNote(in.maskingNote());
    e.setNullHandling(in.nullHandling());
    e.setNullFill(in.nullFill());
    if (e.getId() == null) layers.insert(e); else layers.updateById(e);
    if (old != null && !old.equals(layer)) {
      List<WarehouseTableEntity> ts = tables.selectList(Wrappers.<WarehouseTableEntity>lambdaQuery()
          .eq(WarehouseTableEntity::getProjectId, projectId).eq(WarehouseTableEntity::getLayer, old));
      for (WarehouseTableEntity t : ts) {
        t.setLayer(layer);
        tables.updateById(t);
      }
    }
    return toLayer(e);
  }

  @Transactional
  public void deleteLayer(String projectId, String layer) {
    access.requireMember(projectId, "spec:write");
    if (tables.selectCount(Wrappers.<WarehouseTableEntity>lambdaQuery()
        .eq(WarehouseTableEntity::getProjectId, projectId)
        .eq(WarehouseTableEntity::getLayer, layer)) > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "分层 " + layer + " 仍被表引用，无法删除");
    }
    layers.delete(Wrappers.<LayerRuleEntity>lambdaQuery()
        .eq(LayerRuleEntity::getProjectId, projectId).eq(LayerRuleEntity::getLayer, layer));
  }

  public List<ApiModels.GradeDto> listGrades(String projectId) {
    access.requireMember(projectId, "spec:read");
    return grades.selectList(Wrappers.<DataGradeEntity>lambdaQuery().eq(DataGradeEntity::getProjectId, projectId))
        .stream().map(this::toGrade).toList();
  }

  @Transactional
  public ApiModels.GradeDto saveGrade(String projectId, ApiModels.GradeDto in) {
    access.requireMember(projectId, "spec:write");
    String code = reqCode(in.code());
    DataGradeEntity exist = in.id() == null ? null : grades.selectById(in.id());
    long clash = grades.selectCount(Wrappers.<DataGradeEntity>lambdaQuery()
        .eq(DataGradeEntity::getProjectId, projectId).eq(DataGradeEntity::getCode, code)
        .ne(exist != null, DataGradeEntity::getId, exist == null ? "" : exist.getId()));
    if (clash > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "等级编码必须项目内唯一");
    DataGradeEntity e = exist == null ? new DataGradeEntity() : exist;
    String prev = e.getCode();
    if (exist == null) e.setId(in.id() == null || in.id().isBlank() ? "g-" + System.currentTimeMillis() : in.id());
    e.setProjectId(projectId);
    e.setCode(code);
    e.setName(nz(in.name()));
    e.setLevel(in.level());
    e.setColor(in.color());
    e.setQueryPolicy(in.query() == null ? "login" : in.query());
    e.setExportPolicy(in.export() == null ? "approval" : in.export());
    e.setNote(nz(in.note()));
    e.setExamples(nz(in.examples()));
    if (exist == null) grades.insert(e); else grades.updateById(e);
    if (prev != null && !prev.equals(code)) renameGradeRefs(projectId, prev, code);
    return toGrade(e);
  }

  @Transactional
  public void deleteGrade(String projectId, String id) {
    access.requireMember(projectId, "spec:write");
    DataGradeEntity e = grades.selectById(id);
    if (e == null || !projectId.equals(e.getProjectId())) return;
    if (gradeUsed(projectId, e.getCode())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "等级 " + e.getCode() + " 仍被表或字段引用，无法删除");
    }
    grades.deleteById(id);
  }

  public List<ApiModels.RootDto> listRoots(String projectId) {
    access.requireMember(projectId, "spec:read");
    return roots.selectList(Wrappers.<WordRootEntity>lambdaQuery().eq(WordRootEntity::getProjectId, projectId))
        .stream().map(this::toRoot).toList();
  }

  @Transactional
  public ApiModels.RootDto saveRoot(String projectId, ApiModels.RootDto in) {
    access.requireMember(projectId, "spec:write");
    WordRootEntity exist = in.id() == null ? null : roots.selectById(in.id());
    long clash = roots.selectCount(Wrappers.<WordRootEntity>lambdaQuery()
        .eq(WordRootEntity::getProjectId, projectId).eq(WordRootEntity::getCode, in.code())
        .ne(exist != null, WordRootEntity::getId, exist == null ? "" : exist.getId()));
    if (clash > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "词根编码已存在");
    WordRootEntity e = exist == null ? new WordRootEntity() : exist;
    if (exist == null) e.setId(in.id() == null || in.id().isBlank() ? "r-" + System.currentTimeMillis() : in.id());
    e.setProjectId(projectId);
    e.setKind(in.kind());
    e.setCode(in.code());
    e.setZh(nz(in.zh()));
    e.setEn(nz(in.en()));
    e.setDomain(in.domain());
    e.setFormula(in.formula());
    e.setDataType(in.dataType());
    e.setFormat(in.format());
    if (exist == null) roots.insert(e); else roots.updateById(e);
    return toRoot(e);
  }

  @Transactional
  public void deleteRoot(String projectId, String id) {
    access.requireMember(projectId, "spec:write");
    WordRootEntity e = roots.selectById(id);
    if (e == null || !projectId.equals(e.getProjectId())) return;
    roots.deleteById(id);
  }

  @Transactional
  public void syncSpec(String projectId, ApiModels.SpecSync body) {
    access.requireMember(projectId, "spec:write");
    if (body.domains() != null) {
      for (ApiModels.DomainDto d : body.domains()) saveDomain(projectId, d);
    }
    if (body.layers() != null) {
      java.util.Set<String> keep = new java.util.LinkedHashSet<>();
      for (ApiModels.LayerDto l : body.layers()) {
        if (l.layer() != null && !l.layer().isBlank()) keep.add(reqCode(l.layer()));
      }
      for (LayerRuleEntity exist : layers.selectList(
          Wrappers.<LayerRuleEntity>lambdaQuery().eq(LayerRuleEntity::getProjectId, projectId))) {
        if (keep.contains(exist.getLayer())) continue;
        if (tables.selectCount(Wrappers.<WarehouseTableEntity>lambdaQuery()
            .eq(WarehouseTableEntity::getProjectId, projectId)
            .eq(WarehouseTableEntity::getLayer, exist.getLayer())) > 0) {
          continue;
        }
        layers.deleteById(exist.getId());
      }
      for (ApiModels.LayerDto l : body.layers()) saveLayer(projectId, null, l);
    }
    if (body.grades() != null) {
      for (ApiModels.GradeDto g : body.grades()) saveGrade(projectId, g);
    }
    if (body.roots() != null) {
      for (ApiModels.RootDto r : body.roots()) saveRoot(projectId, r);
    }
  }

  @Transactional
  public void bootstrap(String projectId) {
    access.requireProject(projectId);
    seedStdLayers(projectId);
    if (grades.selectCount(Wrappers.<DataGradeEntity>lambdaQuery().eq(DataGradeEntity::getProjectId, projectId)) == 0) {
      upsertGrade(projectId, "L1", "公开", 1, "green", "allow", "allow", "已对外或可公开的统计口径，不含个人与资金明细。", "日活、曝光量、类目 GMV 汇总");
      upsertGrade(projectId, "L2", "内部", 2, "blue", "login", "approval", "企业内部运营数据，登录即可查，导出需审批。", "订单明细、投放计划、代码位报表");
      upsertGrade(projectId, "L3", "敏感", 3, "orange", "approval", "forbid", "含个人标识或设备标识，查询需审批，禁止明文导出。", "手机号、设备号、用户 ID 映射");
      upsertGrade(projectId, "L4", "机密", 4, "red", "forbid", "forbid", "资金账户、密钥、未发布策略。禁止直接查询与导出。", "账户余额、密钥、未发布定价");
    }
    copyTechTimeRoots(projectId);
  }

  private void copyTechTimeRoots(String projectId) {
    List<WordRootEntity> src = roots.selectList(Wrappers.<WordRootEntity>lambdaQuery()
        .eq(WordRootEntity::getProjectId, "p-trade")
        .ne(WordRootEntity::getKind, "biz"));
    for (WordRootEntity r : src) {
      long n = roots.selectCount(Wrappers.<WordRootEntity>lambdaQuery()
          .eq(WordRootEntity::getProjectId, projectId).eq(WordRootEntity::getCode, r.getCode()));
      if (n > 0) continue;
      WordRootEntity c = new WordRootEntity();
      c.setId(r.getId() + "-" + projectId);
      c.setProjectId(projectId);
      c.setKind(r.getKind());
      c.setCode(r.getCode());
      c.setZh(r.getZh());
      c.setEn(r.getEn());
      c.setDataType(r.getDataType());
      c.setFormat(r.getFormat());
      roots.insert(c);
    }
  }

  private void upsertGrade(String pid, String code, String name, int level, String color, String query, String export, String note, String examples) {
    DataGradeEntity e = new DataGradeEntity();
    e.setId("g-" + pid + "-" + code);
    e.setProjectId(pid);
    e.setCode(code);
    e.setName(name);
    e.setLevel(level);
    e.setColor(color);
    e.setQueryPolicy(query);
    e.setExportPolicy(export);
    e.setNote(note);
    e.setExamples(examples);
    grades.insert(e);
  }

  private void seedStdLayers(String projectId) {
    long n = layers.selectCount(Wrappers.<LayerRuleEntity>lambdaQuery().eq(LayerRuleEntity::getProjectId, projectId));
    if (n > 0) return;
    insertLayer(projectId, "ODS", "ods_[源系统]_[表名]_[增量标记]", "3-7天", "forbid", "原始数据，镜像同步");
    insertLayer(projectId, "DWD", "dwd_[主题域]_[业务过程]_[粒度]_[周期]", "永久", "forbid", "清洗后明细，维度退化");
    insertLayer(projectId, "DWS", "dws_[主题域]_[业务过程]_[统计周期]", "永久", "approval", "轻度汇总，面向分析");
    insertLayer(projectId, "ADS", "ads_[应用]_[业务场景]", "按需", "allow", "应用层，直接服务");
  }

  private void insertLayer(String projectId, String layer, String naming, String retention, String serve, String note) {
    LayerRuleEntity c = new LayerRuleEntity();
    c.setProjectId(projectId);
    c.setLayer(layer);
    c.setNaming(naming);
    c.setRetention(retention);
    c.setServe(serve);
    c.setNote(note);
    layers.insert(c);
  }

  private LayerRuleEntity findLayer(String projectId, String layer) {
    return layers.selectOne(Wrappers.<LayerRuleEntity>lambdaQuery()
        .eq(LayerRuleEntity::getProjectId, projectId).eq(LayerRuleEntity::getLayer, layer));
  }

  private boolean gradeUsed(String projectId, String code) {
    List<WarehouseTableEntity> ts = tables.selectList(
        Wrappers.<WarehouseTableEntity>lambdaQuery().eq(WarehouseTableEntity::getProjectId, projectId));
    for (WarehouseTableEntity t : ts) {
      if (code.equals(t.getGrade())) return true;
      List<TableColumnEntity> cols = columns.selectList(
          Wrappers.<TableColumnEntity>lambdaQuery().eq(TableColumnEntity::getTableId, t.getId()));
      for (TableColumnEntity c : cols) {
        if (code.equals(c.getGrade())) return true;
      }
    }
    return false;
  }

  private void renameGradeRefs(String projectId, String prev, String code) {
    List<WarehouseTableEntity> ts = tables.selectList(
        Wrappers.<WarehouseTableEntity>lambdaQuery().eq(WarehouseTableEntity::getProjectId, projectId));
    for (WarehouseTableEntity t : ts) {
      if (prev.equals(t.getGrade())) {
        t.setGrade(code);
        tables.updateById(t);
      }
      List<TableColumnEntity> cols = columns.selectList(
          Wrappers.<TableColumnEntity>lambdaQuery().eq(TableColumnEntity::getTableId, t.getId()));
      for (TableColumnEntity c : cols) {
        if (prev.equals(c.getGrade())) {
          c.setGrade(code);
          columns.update(c, Wrappers.<TableColumnEntity>lambdaQuery()
              .eq(TableColumnEntity::getTableId, t.getId()).eq(TableColumnEntity::getName, c.getName()));
        }
      }
    }
  }

  private ApiModels.DomainDto toDomain(DomainEntity e) {
    return new ApiModels.DomainDto(
        e.getId(), e.getProjectId(), e.getCode(), e.getName(), e.getDefinition(),
        e.getBizOwner(), e.getTechOwner(), e.getDataOwner(),
        Jsons.strings(e.getRelated()), Jsons.strings(e.getCoreEntities()));
  }

  private ApiModels.LayerDto toLayer(LayerRuleEntity e) {
    return new ApiModels.LayerDto(
        e.getLayer(), e.getProjectId(), e.getNaming(), e.getRetention(), e.getServe(), e.getNote(),
        e.getFieldFormat(), e.getTimeFormat(), e.getMasking(), e.getMaskingNote(),
        e.getNullHandling(), e.getNullFill());
  }

  private ApiModels.GradeDto toGrade(DataGradeEntity e) {
    return new ApiModels.GradeDto(
        e.getId(), e.getProjectId(), e.getCode(), e.getName(), e.getLevel() == null ? 0 : e.getLevel(),
        e.getColor(), e.getQueryPolicy(), e.getExportPolicy(), e.getNote(), e.getExamples());
  }

  private ApiModels.RootDto toRoot(WordRootEntity e) {
    return new ApiModels.RootDto(
        e.getId(), e.getProjectId(), e.getKind(), e.getCode(), e.getZh(), e.getEn(),
        e.getDomain(), e.getFormula(), e.getDataType(), e.getFormat());
  }

  private static String reqCode(String code) {
    if (code == null || code.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "编码必填");
    return code.trim().toUpperCase();
  }

  private static String nz(String s) { return s == null ? "" : s; }
}
