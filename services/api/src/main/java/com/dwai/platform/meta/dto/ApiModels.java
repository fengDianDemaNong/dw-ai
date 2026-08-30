package com.dwai.platform.meta.dto;

import java.util.List;
import java.util.Map;

/** 与控制台 / 01-spec-modeling 对齐的 JSON 形态。 */
public final class ApiModels {
  private ApiModels() {}

  public record Me(
      String userId,
      String displayName,
      String tenantId,
      String tenantCode,
      String tenantName,
      String username,
      String authMode,
      boolean platformAdmin,
      String tenantRole,
      String landing,
      String landingProjectId,
      boolean needSelectTenant,
      String deployMode) {}

  public record TenantDto(String id, String code, String name, String owner, String status, java.util.List<String> modules) {}

  public record OrgUserDto(
      String id, String username, String displayName, String status, String tenantRole, boolean platformAdmin) {}

  public record LoginRes(
      String token,
      boolean needSelectTenant,
      boolean platformAdmin,
      String displayName,
      String userId,
      java.util.List<TenantDto> tenants) {}

  public record LoginReq(String username, String password) {}

  public record SelectTenantReq(String tenantId) {}

  public record CreateOrgUserReq(
      String username, String displayName, String password, String tenantRole, String existingUserId) {}

  public record PatchOrgUserReq(
      String status,
      String tenantRole,
      String password,
      String projectId,
      String displayName,
      String username,
      java.util.List<MemberDto> memberships) {}

  public record EnterTenantReq(String tenantId, String code) {}

  public record TransferAdminReq(String userId) {}

  public record GrantProjectScope(String projectId, String role) {}

  public record GrantReq(
      String kind,
      String expiresAt,
      Boolean permanent,
      java.util.List<String> modules,
      java.util.List<String> projectIds,
      java.util.List<GrantProjectScope> projectScopes,
      String defaultRole) {}

  public record AppearanceDto(String theme, String menuPos) {}

  public record LlmDto(boolean enabled, String provider, String baseUrl, String model, boolean hasKey, String apiKey) {}

  public record GrantDto(
      String id, String tenantId, String code, String kind, String expiresAt, boolean valid, String createdAt,
      java.util.List<String> modules, java.util.List<String> projectIds,
      java.util.List<GrantProjectScope> projectScopes, String defaultRole) {}

  public record TableVersionDto(
      String id, String tableId, String projectId, int version, String note, String createdBy, String createdAt,
      java.util.Map<String, Object> snapshot) {}

  public record RestoreReq(String note) {}

  public record AiChatReq(String message, String tableId, java.util.List<java.util.Map<String, Object>> history) {}

  public record AiApplyReq(java.util.List<TableDraft> tables) {}

  public record TableDraft(
      String mode, String tableId, String layer, String name, String comment, String domain,
      String grain, String period, String partition, String grade, java.util.List<ColumnDto> columns) {}

  public record ProfileReq(String displayName) {}

  public record PasswordReq(String currentPassword, String newPassword) {}

  public record CreatePlatformUserReq(String username, String displayName, String password) {}

  public record PatchPlatformUserReq(String status, String password) {}

  public record CreateAdminTenantReq(
      String code,
      String name,
      String owner,
      java.util.List<String> modules,
      String adminUsername,
      String adminPassword,
      String adminDisplayName,
      String adminUserId) {}

  public record PatchAdminTenantReq(String status, String name, String owner, java.util.List<String> modules) {}

  public record LicenseDto(String tenantId, List<String> modules) {}

  public record ProjectDto(
      String id, String tenantId, String code, String name, String description, String owner, String createdAt, String status) {}

  public record PatchProjectReq(String name, String description, String owner, String status, String code) {}

  public record MemberDto(String projectId, String userId, String role) {}

  public record DomainDto(
      String id, String projectId, String code, String name, String definition,
      String bizOwner, String techOwner, String dataOwner,
      List<String> related, List<String> coreEntities) {}

  public record LayerDto(
      String layer, String projectId, String naming, String retention, String serve, String note,
      String fieldFormat, String timeFormat, String masking, String maskingNote,
      String nullHandling, String nullFill) {}

  public record GradeDto(
      String id, String projectId, String code, String name, int level, String color,
      String query, String export, String note, String examples) {}

  public record RootDto(
      String id, String projectId, String kind, String code, String zh, String en,
      String domain, String formula, String dataType, String format) {}

  public record ColumnDto(
      String name, String type, String comment, Boolean nullable, String defaultValue,
      Boolean sensitive, String grade, List<String> enumValues) {}

  public record TableDto(
      String id, String projectId, String layer, String name, String comment, String domain,
      String sourceSystem, String grain, String period, List<ColumnDto> columns,
      String partition, String storedAs, String status, String createdFrom, String grade) {}

  public record DraftDto(
      String id, String projectId, String sourceTableId, String targetLayer, String domainCode,
      Double domainConfidence, String grain, List<String> primaryKeys,
      List<Map<String, Object>> fieldTags, String ddl, String etlSql,
      List<Map<String, Object>> qualityRules, List<Map<String, Object>> specIssues,
      String status, String createdAt) {}

  public record SessionDto(
      Me user,
      List<TenantDto> tenants,
      List<ProjectDto> projects,
      List<MemberDto> members,
      List<LicenseDto> licenses) {}

  public record SnapshotDto(
      ProjectDto project,
      List<MemberDto> members,
      List<DomainDto> domains,
      List<LayerDto> layers,
      List<GradeDto> grades,
      List<RootDto> roots,
      List<TableDto> tables,
      List<DraftDto> drafts) {}

  public record SpecSync(
      List<DomainDto> domains,
      List<LayerDto> layers,
      List<GradeDto> grades,
      List<RootDto> roots) {}

  public record CreateProjectReq(
      String code, String name, String description, String owner, String adminUserId, Boolean bootstrapSpec) {}

  public record CreateTenantReq(String code, String name, String owner) {}

  public record MemberReq(String userId, String role) {}
}
