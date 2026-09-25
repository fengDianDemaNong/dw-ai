package com.dwai.platform.meta;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 权限词的<b>形状</b>判定：严格两段式 {@code 域:动作}，动作取自固定五档。
 *
 * <h2>它只管形状，不管归属</h2>
 *
 * 「metadata 这个产品认不认 {@code model:write}」是归属问题，真源有两处、都不在这里：
 * 各产品自报的词表（菜单候选里的 {@code perms}）与 {@code dw-common} 的
 * {@code Perms.java}。本类只回答「这个词写对形状了吗」。
 *
 * <p>两件事必须分开：形状错（{@code catalog:edit}、{@code catalogread}）在任何情况下
 * 都该拒 —— 它是拼写错误；归属错则要看词表拿不拿得到（见 {@code ProductRoleService}），
 * 拿不到时只能放行，否则服务的页面地址一没配，管理员就建不了角色。
 *
 * <h2>与另外两份的关系</h2>
 *
 * 动作五档 {@code read/write/admin/publish/member} 与
 * {@code packages/engine/src/iam.ts:17-29} 的 {@code Perm} 联合类型、
 * {@code dw-common} 的 {@code Perms.java} 是同一套。那两份是<b>权威定义</b>
 * （本方案明确不改它们），这里是 org 侧为了校验抄的第三份 —— 因此
 * {@code PermWordsTest} 用反射读 {@code Perms} 的全部权限词并逐个断言本类认它，
 * 让三份漂移时测试变红，而不是等管理员填了个词才发现判否。
 *
 * <p>org 侧不能直接用 {@code Perms} 判形状：它的 {@code has} 需要 (产品, 角色) 两个
 * 上下文才回答，而「这个词本身长得对不对」不需要任何上下文。
 */
public final class PermWords {

  private PermWords() {}

  /**
   * 动作白名单。没有「编辑」「查看」这种档位 —— 中文的「编辑/查看」是菜单上的按钮文案，
   * 落到权限词上是 {@code write} 与 {@code read}。
   */
  public static final Set<String> ACTIONS = Set.of("read", "write", "admin", "publish", "member");

  /** 域与动作都只允许小写字母数字，域不以数字开头。 */
  private static final Pattern SHAPE = Pattern.compile("^[a-z][a-z0-9]*:[a-z]+$");

  /** 空的权限词是<b>合法</b>的：它表示「这个菜单不判权，进得来就看得见」。 */
  public static boolean isWellFormed(String perm) {
    if (perm == null) return false;
    String p = perm.trim();
    if (p.isEmpty()) return true;
    if (!SHAPE.matcher(p).matches()) return false;
    return ACTIONS.contains(p.substring(p.indexOf(':') + 1));
  }

  /**
   * 形状不对就 400。
   *
   * @param where 出错时告诉调用方是哪个字段，如「权限词」「角色 product-roles/x 的权限词」
   */
  public static String requireWellFormed(String perm, String where) {
    if (!isWellFormed(perm)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          where + "「" + perm + "」不是合法的权限词。格式是「域:动作」，动作只能是 " + ACTIONS);
    }
    return perm == null ? "" : perm.trim();
  }
}
