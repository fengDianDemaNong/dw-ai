package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 一个产品的角色定义（见 {@code V20__product_roles.sql}）。
 *
 * <p><b>它是 {@code Perms.java} 那份硬编码矩阵的数据化版本</b>。判权链路
 * （{@code AccessService}）改成「先查本表、查不到再回落 {@code Perms}」，
 * 所以本表里没有的行仍按老规则判 —— 这正是标准/独立模式与历史数据不需要
 * 一起迁移的原因。
 *
 * <p>权限词不在本表，在同一条 {@code product_role_perms} 里（一个角色多个词，
 * 用单独一张表而不是 JSON 列：MyBatis-Plus 与两套方言对 JSON 列的支持都要额外
 * 配置，而这里的关系是一对多的定长集合，拆表最省事也最好查）。
 *
 * <p>字段与列一一对应、没有 JSON 列，所以不需要 {@code autoResultMap}。
 */
@TableName("product_roles")
public class ProductRoleEntity {
  /** {@code prole-<uuid16>}；内置角色是确定性的 {@code prole-<product>-<code>}。 */
  @TableId(type = IdType.INPUT)
  private String id;
  /** 产品码，见 {@link com.dwai.platform.meta.ProductCodes#KNOWN}。 */
  private String product;
  /**
   * 角色码，写进 {@code project_members.role} 的<b>就是它</b>。
   *
   * <p>宽度对齐 {@code project_members.role VARCHAR(32)}（见迁移注释）：两条线
   * 用同一个字符串做判权，列宽不同会在写入时被悄悄截断，截断后两边对不上。
   *
   * <p>产品与角色码一起构成角色身份（唯一键 {@code uk_product_role}），
   * 所以两者都不允许改 —— 改它就是「换一个角色」，该走删+建。
   */
  private String code;
  /** 给人看的名字（「规范管理员」「目录管理员」）。 */
  private String label;
  /** 一句话说明，管理页上直接展示。可以为空串。 */
  private String hint;
  /**
   * 是否该产品的管理角色。
   *
   * <p>它有一个硬用途：{@code AccessService.roleOf} 对<b>租户管理员</b>是短路返回
   * 字符串 {@code "admin"} 的，所以每个有角色的产品必须恰有一个 {@code is_admin=TRUE}
   * 的行，否则租户管理员进了这个产品反而判否。
   *
   * <p>不变量由 {@code ProductRoleService} 守着（切换时自动降级原管理角色）。
   */
  private Boolean isAdmin;
  /**
   * 内置：不可删、不可改角色码。
   *
   * <p>内置三角色是 {@code Perms.java} 矩阵的投影，删掉就没有兜底可比对了；
   * 它们可以被改 label / hint / 权限词 —— 「内置」只约束身份，不约束内容。
   */
  private Boolean builtin;
  private Integer sortOrder;
  private OffsetDateTime createdAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getProduct() { return product; }
  public void setProduct(String product) { this.product = product; }
  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }
  public String getLabel() { return label; }
  public void setLabel(String label) { this.label = label; }
  public String getHint() { return hint; }
  public void setHint(String hint) { this.hint = hint; }
  public Boolean getIsAdmin() { return isAdmin; }
  public void setIsAdmin(Boolean isAdmin) { this.isAdmin = isAdmin; }
  public Boolean getBuiltin() { return builtin; }
  public void setBuiltin(Boolean builtin) { this.builtin = builtin; }
  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
