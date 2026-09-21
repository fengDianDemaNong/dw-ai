<template>
  <div class="ratio">
    <div class="ratio-head">
      <span class="ratio-label" :title="label">
        {{ label }}
        <Tooltip v-if="hint" :title="hint"><span class="stat-hint">?</span></Tooltip>
      </span>
      <span class="ratio-num">
        <template v-if="display === 'count'">{{ value.toLocaleString() }}</template>
        <span v-else-if="pct === null" class="muted">{{ emptyText ?? '—' }}</span>
        <template v-else>
          <span class="muted">{{ value.toLocaleString() }} / {{ total.toLocaleString() }} · </span>
          <b>{{ pct }}%</b>
        </template>
      </span>
    </div>
    <div class="ratio-track">
      <div v-if="pct !== null" class="ratio-fill" :style="{ width: pct + '%' }" />
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue';
import { Tooltip } from 'ant-design-vue';

/**
 * 一条比率 / 计数横条。
 *
 * 覆盖率、分布、元数据服务三处都用它，一屏上下来二十多条 —— 所以做成组件而不是
 * 在页面里写三遍。
 *
 * 不用 antd 的 Progress：每一处都要「名称 + 条 + 41 / 116 · 35%」三段布局，
 * Progress 只省下 6px 高的那个 div，却处理不了<b>分母为 0 时必须显示「—」而不是 0%</b>，
 * 也塞不进「分子 / 分母」那对原始数。
 */
const props = defineProps<{
  label: string;
  /** 分子；display='count' 时就是计数本身 */
  value: number;
  /** 分母；display='count' 时传该组的最大值，只用来归一化条长 */
  total: number;
  hint?: string;
  /** 'ratio' 显示「41 / 116 · 35%」；'count' 只显示数字 */
  display?: 'ratio' | 'count';
  /** 分母为 0 时的占位 */
  emptyText?: string;
}>();

const pct = computed(() => {
  // 0/0 不是 0%，是「没有可算的东西」。返回 null，渲染侧只画空轨道
  if (props.total <= 0) return null;
  if (props.value >= props.total) return 100;
  if (props.value <= 0) return 0;
  // 向下取整：99.6% 四舍五入成 100% 会被读成「全覆盖」，而实际上还差几张表 ——
  // 覆盖率这种指标宁可偏保守，也不能谎报满分。
  // 反过来非零值至少给 1%，否则 1/5000 画出来是一条看不见的线，像是没数据
  return Math.max(1, Math.floor((props.value / props.total) * 100));
});
</script>

<style scoped>
.ratio {
  margin-bottom: 10px;
}

.ratio:last-child {
  margin-bottom: 0;
}

.ratio-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 13px;
}

.ratio-label {
  color: #4b5563;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ratio-num {
  margin-left: auto;
  white-space: nowrap;
  color: #1f2937;
  font-variant-numeric: tabular-nums;
}

.ratio-track {
  height: 6px;
  border-radius: 3px;
  /* 轨道用同色系浅一档，整条能从头读到尾 —— 灰底会让「空」和「背景」分不开 */
  background: #e6f4ff;
  overflow: hidden;
}

.ratio-fill {
  height: 100%;
  border-radius: 3px;
  background: #1677ff;
}
</style>
