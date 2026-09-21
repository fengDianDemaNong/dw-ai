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
    <!-- SQL文件图标 -->
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <path d="M14 2v6h6" />

    <!-- SQL字母标识 -->
    <path d="M8 13.5c.5-.7 1.2-1 2-1 1.4 0 2 1.4 2 2.5 0 1.1-.6 2.5-2 2.5-.8 0-1.5-.3-2-1" />
    <path d="M14 12v6" />
    <path d="M17 15l-2-2" />
    <path d="M15 13l2 2" />
  </svg>
</template>

<script lang="ts" setup>
interface IconFormatProps {
  className?: string;
  title?: string;
}

const props = withDefaults(defineProps<IconFormatProps>(), {
  className: '',
  title: '格式化 SQL',
});
</script>