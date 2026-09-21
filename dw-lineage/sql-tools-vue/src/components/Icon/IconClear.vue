<template>
  <svg
    :width="20"
    :height="20"
    viewBox="0 0 24 24"
    :stroke-width="1.75"
    stroke-linecap="round"
    stroke-linejoin="round"
    :class="className"
    fill="none"
    stroke="currentColor"
    :title="title"
  >
    <!-- 文档底板 -->
    <path d="M21 11v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8" />
    <!-- 清除符号 -->
    <path d="M10 12l4 4m0-4l-4 4" />
    <!-- 清空指示 -->
    <path d="M9 8h6" />
    <path d="M9 16h6" />
  </svg>
</template>

<script lang="ts" setup>
interface IconClearProps {
  className?: string;
  title?: string;
}

const props = withDefaults(defineProps<IconClearProps>(), {
  className: '',
  title: '清空内容',
});
</script>