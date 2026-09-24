package com.dwai.lineage.dto;

/**
 * 分布 / 计数条的一行：一个名字配一个计数。
 *
 * <p>{@code name} 允许为 null（没有库名的表、没写方言的版本都会落到这一档）。
 * <b>不要在服务端补成「未指定」之类的字符串</b> —— 那是展示层的措辞，
 * 换个语言、换个页面就得改，而 null 是数据本身的事实。前端已经统一兜底。
 */
public record StatCount(String name, int count) {
}
