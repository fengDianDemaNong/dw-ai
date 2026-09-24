package com.dwai.lineage.dto;

/**
 * 规则试算结果。
 *
 * <p>只回「是不是临时」不够用：配了七八条规则时，用户需要知道命中的是<b>哪一条</b>，
 * 才能判断是不是自己想要的那条 —— 尤其是通配符和正则混用、
 * 而两者对 {@code tmp_*} 的解释完全相反的时候。
 *
 * @param temp          按当前规则是否判为临时
 * @param matchedRuleId 命中的规则 id；未命中为 null
 * @param explanation   给页面直接显示的一句话
 */
public record TempRuleTestResponse(String input,
                                   boolean temp,
                                   Long matchedRuleId,
                                   String matchedPattern,
                                   String explanation) {
}
