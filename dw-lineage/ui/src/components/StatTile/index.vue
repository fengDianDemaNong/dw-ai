<template>
  <component
    :is="to ? 'router-link' : 'div'"
    :to="to"
    class="stat-tile"
    :class="{ 'stat-tile-link': to }"
  >
    <div class="stat-value" :class="`tone-${tone ?? 'default'}`">{{ text }}</div>
    <div class="stat-label">
      {{ label }}
      <Tooltip v-if="hint" :title="hint"><span class="stat-hint">?</span></Tooltip>
    </div>
    <div v-if="sub" class="stat-sub">{{ sub }}</div>
  </component>
</template>

<script lang="ts" setup>
import { computed } from 'vue';
import { Tooltip } from 'ant-design-vue';

/**
 * 概览页的 KPI 方块。
 *
 * 原先这套样式散在 overview.vue 的 scoped style 里，改版后同一屏要用十次、
 * 两个标签页各一行，再复制就是第三、第四份了。
 *
 * 用 `<component :is>` 在 router-link 与 div 之间切换，而不是给 div 绑 @click
 * 调 router.push —— 后者做出来的东西中键点不开、也没法「在新标签页打开」，
 * 看着像链接却不是链接。
 */
const props = defineProps<{
  label: string;
  /** number 走 toLocaleString()；string 原样输出，用于「—」这类占位 */
  value: number | string;
  /** 第二行小字，如「其中临时表 12」 */
  sub?: string;
  /** 口径说明。非空时在 label 后渲染一个 ? 圆点 */
  hint?: string;
  /** 有值则整块可点 */
  to?: string;
  /** 需要示警的数字（孤立表、失败任务）用 warn / danger */
  tone?: 'default' | 'warn' | 'danger';
}>();

const text = computed(() =>
  typeof props.value === 'number' ? props.value.toLocaleString() : props.value
);
</script>

<style scoped>
.stat-tile {
  display: block;
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 16px;
  /* router-link 默认带下划线与主题色，这里要的是一整块卡片 */
  color: inherit;
  text-decoration: none;
}

.stat-tile-link {
  cursor: pointer;
}

.stat-tile-link:hover {
  border-color: #91caff;
}

.stat-value {
  font-size: 26px;
  font-weight: 500;
  color: #1f2937;
  line-height: 1.2;
  /* 等宽数字：一行五个方块，位数不同也不会左右跳 */
  font-variant-numeric: tabular-nums;
}

.tone-warn {
  color: #d48806;
}

.tone-danger {
  color: #cf1322;
}

.stat-label {
  margin-top: 4px;
  font-size: 13px;
  color: #6b7280;
}

.stat-sub {
  margin-top: 2px;
  font-size: 12px;
  color: #9ca3af;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
