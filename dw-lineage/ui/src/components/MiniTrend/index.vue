<template>
  <div class="trend">
    <div v-if="!hasAny" class="trend-empty" :style="{ height: h + 'px' }">
      <span class="muted">{{ emptyText ?? '近 30 天没有记录' }}</span>
    </div>

    <template v-else>
      <svg
        class="trend-svg"
        :viewBox="`0 0 ${width} ${h}`"
        :height="h"
        preserveAspectRatio="none"
        role="img"
        :aria-label="ariaLabel"
      >
        <!-- 基线。实线发丝、一档灰：虚线会被读成「阈值」，而这只是坐标轴 -->
        <rect x="0" :y="h - 1" :width="width" height="1" fill="#f0f0f0" />

        <rect
          v-for="b in bars"
          :key="b.day"
          :x="b.x"
          :y="b.y"
          :width="BAR"
          :height="b.height"
          rx="1"
          fill="#1677ff"
        />

        <!-- 命中区盖满整格（含 count 为 0 的那天），比去点 8px 宽的柱子好按得多 -->
        <rect
          v-for="b in bars"
          :key="`hit-${b.day}`"
          :x="b.x - (SLOT - BAR) / 2"
          y="0"
          :width="SLOT"
          :height="h"
          fill="transparent"
          @mouseenter="hover = b"
          @mouseleave="hover = null"
        />
      </svg>

      <div class="trend-axis">
        <span>{{ shortDay(data[0]?.day) }}</span>
        <span>{{ shortDay(data[data.length - 1]?.day) }}</span>
      </div>

      <div v-if="hover" class="trend-tip" :style="tipStyle">
        {{ shortDay(hover.day) }} · <b>{{ hover.count }}</b> 次
      </div>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue';
import type { DayCount } from '../../services/api';

/**
 * 逐日趋势的小柱图。
 *
 * 为这一个图去装 echarts（约 1MB）不划算，项目至今一个图表库都没有，
 * 只有血缘图用的 g6 —— 那是画 DAG 的，帮不上忙。所以手写这几十行。
 *
 * <b>柱不是折线</b>：数据是「每天发生了几次解析」的离散计数，30 天里大半是 0，
 * 折线画出来是一条贴着底、时不时冒个尖的平线，既不好看也读不出东西。
 *
 * <b>没有 ResizeObserver</b>：`viewBox` + `preserveAspectRatio="none"` + 宽度 100%
 * 让浏览器去缩放，整个组件是纯 computed，不碰布局。代价是柱间那 2px 的空隙也跟着
 * 缩放，30 格放进一张卡里大约变成 2.7px —— 看不出来，换来的是零运行时开销。
 */
const props = defineProps<{
  data: DayCount[];
  /** 画布高度 px，宽度自适应容器 */
  height?: number;
  emptyText?: string;
}>();

/** 每天一格：8 宽的柱 + 2 的留白。 */
const SLOT = 10;
const BAR = 8;

const h = computed(() => props.height ?? 56);
const width = computed(() => Math.max(1, props.data.length) * SLOT);

/** 全 0 和空数组都当空态：画一排贴着底的零高柱子，不如直说没有记录。 */
const hasAny = computed(() => props.data.some((d) => d.count > 0));

const bars = computed(() => {
  const max = Math.max(1, ...props.data.map((d) => d.count));
  return props.data.map((d, i) => {
    // 0 就是 0，不画任何东西 —— 给「那天什么都没发生」画一根柱子是撒谎。
    // 但 1/200 算出来是 0.28px，渲染出来等于没有，所以非零一律至少 2px
    const height =
      d.count === 0
        ? 0
        : Math.max(2, Math.round((d.count / max) * (h.value - 2)));
    return {
      ...d,
      x: i * SLOT + (SLOT - BAR) / 2,
      y: h.value - height,
      height,
    };
  });
});

const hover = ref<{ day: string; count: number; x: number } | null>(null);

/**
 * 提示框跟着格子走，按百分比定位。
 *
 * SVG 内部是 viewBox 坐标、外面这个 div 是 CSS 像素，两套坐标系不通用；
 * 换算成百分比就都对了，也不用去读元素实际宽度。
 */
const tipStyle = computed(() => {
  if (!hover.value) return {};
  const ratio = (hover.value.x + BAR / 2) / width.value;
  return {
    left: `${ratio * 100}%`,
    // 靠右半边时向左展开，否则贴到卡片边缘会被裁掉
    transform: ratio > 0.6 ? 'translateX(-100%)' : 'translateX(-50%)',
  };
});

/** 一年内的趋势不必显示年份，「08-14」够认了。 */
const shortDay = (day?: string) => day?.slice(5) ?? '';

/** 读屏软件读不了一堆 rect，给整张图一句话摘要。 */
const ariaLabel = computed(() => {
  const total = props.data.reduce((acc, d) => acc + d.count, 0);
  return `近 ${props.data.length} 天共 ${total} 次，逐日柱状图`;
});
</script>

<style scoped>
.trend {
  position: relative;
}

.trend-svg {
  display: block;
  width: 100%;
}

.trend-axis {
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 12px;
  color: #9ca3af;
  font-variant-numeric: tabular-nums;
}

.trend-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  /* 空态也占住同样的高度，切换数据时卡片不会忽高忽低 */
  border-bottom: 1px solid #f0f0f0;
}

.trend-tip {
  position: absolute;
  top: -6px;
  padding: 3px 8px;
  border-radius: 4px;
  background: rgba(31, 41, 55, 0.92);
  color: #fff;
  font-size: 12px;
  white-space: nowrap;
  /* 别挡住自己的命中区，否则鼠标一移到提示上提示就消失，然后又出现 */
  pointer-events: none;
  z-index: 2;
}
</style>
