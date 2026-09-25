package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 角色到权限词的关联行（见 {@code V20__product_roles.sql} 的 {@code product_role_perms}）。
 *
 * <p><b>表的主键是复合的 {@code (role_id, perm)}</b>，而 MyBatis-Plus 的 {@code BaseMapper}
 * 只认单列主键。这里的处理照仓库里已有的同类实体 {@code ProjectMemberEntity}
 * （主键 {@code (project_id, user_id, product)}）：<b>不标 {@code @TableId}</b>，
 * 于是 MP 不生成任何 {@code xxById} 方法 —— 启动日志里那几行
 * 「Can not find table primary key ... Cannot use Mybatis-Plus 'xxById' Method」
 * 就是这个取舍的表现，是刻意的。
 *
 * <p>比标一个 {@code @TableId(roleId)} 更安全：标了之后 {@code selectById} 会变成
 * 「按 role_id 查任意一条」，看着能用、实际拿到的是该角色权限里的随便一项。
 * 本表只走 wrapper（{@code selectList} / {@code delete(Wrapper)} / {@code insert}）。
 *
 * <p>另一个选择是干脆不要实体、在 service 里用 {@code JdbcTemplate} 手写 SQL。
 * 不采用是因为本表与另外两张表的读写要落在同一个事务里，混两套数据访问方式
 * 会让事务边界变得难讲。
 */
@TableName("product_role_perms")
public class ProductRolePermEntity {
  private String roleId;
  /** 严格两段式「域:动作」；合法取值由各产品自报，校验在 service 写入时做。 */
  private String perm;

  public ProductRolePermEntity() {}

  public ProductRolePermEntity(String roleId, String perm) {
    this.roleId = roleId;
    this.perm = perm;
  }

  public String getRoleId() { return roleId; }
  public void setRoleId(String roleId) { this.roleId = roleId; }
  public String getPerm() { return perm; }
  public void setPerm(String perm) { this.perm = perm; }
}
