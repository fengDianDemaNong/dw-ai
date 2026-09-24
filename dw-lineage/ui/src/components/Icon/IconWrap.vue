<template>
  <svg
    :width="20"
    :height="20"
    viewBox="0 0 24 24"
    :stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    :class="className"
    fill="none"
    stroke="currentColor"
  >
    <path d="M3 7h18" />
    <path d="M3 11h11" />
    <path d="M3 15h11" />
    <path d="M14 15l3-3 3 3" />
    <path d="M17 12v8" />
  </svg>
</template>

<script lang="ts" setup>
interface IconWrapProps {
  className?: string;
}

const props = withDefaults(defineProps<IconWrapProps>(), {
  className: '',
});
</script>