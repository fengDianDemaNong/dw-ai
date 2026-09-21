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
    <!-- 前层文档 -->
    <path d="M8 4v12a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7.242a2 2 0 0 0-.602-1.43L16.083 2.57A2 2 0 0 0 14.685 2H10a2 2 0 0 0-2 2z" />
    <!-- 后层文档 -->
    <path d="M16 18v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h2" />
    <!-- 折角效果 -->
    <path d="M15 2v3a2 2 0 0 0 2 2h3" />
  </svg>
</template>

<script lang="ts" setup>
interface IconCopyProps {
  className?: string;
  title?: string;
}

const props = withDefaults(defineProps<IconCopyProps>(), {
  className: '',
  title: '复制全部',
});
</script>