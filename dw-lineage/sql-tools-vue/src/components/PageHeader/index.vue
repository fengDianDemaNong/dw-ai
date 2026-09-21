<template>
  <div class="page-header">
    <div class="page-header-main">
      <h1 class="page-header-title">{{ title }}</h1>
      <span v-if="subtitle" class="page-header-sub">{{ subtitle }}</span>
      <!-- 长说明收在这个 ? 里。以前这些内容是页面顶部一大块 Alert，
           第一次读有用，之后每次进页面都占掉小半屏，把要操作的表格挤到折叠线以下 -->
      <Tooltip v-if="$slots.help" title="查看说明">
        <button type="button" class="page-header-help" @click="helpOpen = true">?</button>
      </Tooltip>
    </div>
    <div v-if="$slots.actions" class="page-header-actions">
      <slot name="actions" />
    </div>
  </div>

  <Drawer
    v-if="$slots.help"
    v-model:open="helpOpen"
    :title="`${title} · 说明`"
    placement="right"
    :width="520"
  >
    <div class="hint page-header-help-body">
      <slot name="help" />
    </div>
  </Drawer>
</template>

<script lang="ts" setup>
import { ref } from 'vue';
import { Drawer, Tooltip } from 'ant-design-vue';

/**
 * 页面标题栏。
 *
 * 三件事：标题、一句话说明、右侧操作区。长说明走 `help` 插槽，收进右侧抽屉。
 *
 * 例外：像「未配置 METADATA_SECRET_KEY，现在就存不进去」这种当下正挡着用户
 * 操作的告警，仍然应该内联成红条放在页面里，不要塞进抽屉 —— 抽屉是给
 * 「读一次就够的背景知识」用的。
 */
defineProps<{
  title: string;
  subtitle?: string;
}>();

const helpOpen = ref(false);
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  min-height: 32px;
}

.page-header-main {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.page-header-title {
  font-size: 18px;
  font-weight: 500;
  color: #1f2937;
  margin: 0;
  white-space: nowrap;
}

.page-header-sub {
  color: #6b7280;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.page-header-help {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  line-height: 16px;
  text-align: center;
  border-radius: 50%;
  border: 1px solid #d1d5db;
  color: #6b7280;
  font-size: 12px;
  cursor: pointer;
  background: transparent;
  align-self: center;
}

.page-header-help:hover {
  border-color: #1677ff;
  color: #1677ff;
}

.page-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.page-header-help-body :deep(p) {
  margin-bottom: 10px;
}

.page-header-help-body :deep(code) {
  background: #f6f7f9;
  border-radius: 3px;
  padding: 1px 5px;
  font-size: 12px;
}
</style>
