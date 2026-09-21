<template>
  <div class="embed">
    <div class="bar">
      <span>独立产品 · {{ label }}（embed）</span>
      <span class="muted">{{ hint }}</span>
    </div>
    <slot />
  </div>
</template>

<script setup lang="ts">
defineProps<{
  label: string;
  hint?: string;
}>();
</script>

<style scoped>
.embed {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.bar {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 12px;
  padding: 8px 12px;
  border: 1px dashed var(--primary);
  border-radius: 8px;
  background: color-mix(in srgb, var(--primary) 8%, transparent);
  font-size: 12px;
}
.muted {
  color: var(--muted);
}
</style>
