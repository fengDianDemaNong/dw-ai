package com.dwai.lineage.dto;

import java.time.LocalDate;

/**
 * 趋势图的一个桶：某一天的解析次数。
 *
 * <p><b>零值桶由服务端补齐</b>，数组恒为 {@code TREND_DAYS} 条、按日期升序。
 * 稀疏数组交给前端补零看着更省事，实际上是个只在特定时段复现的 bug 来源：
 * 浏览器与服务器的「今天」可能差一天（时区、跨零点），前端补出来的 X 轴会整体错位一格。
 */
public record DayCount(LocalDate day, int count) {
}
