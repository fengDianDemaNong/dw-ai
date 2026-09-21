<template>
  <div class="embed" :class="{ frame: src }">
    <div class="bar">
      <span>独立产品 · {{ label }}（embed）</span>
      <span class="muted">{{ hint }}</span>
    </div>
    <iframe v-if="src" :key="src" class="pane" :src="src" :title="label" />
    <slot v-else />
  </div>
</template>

<script setup lang="ts">
defineProps<{
  label: string;
  hint?: string;
  src?: string;
}>();
</script>

<style scoped>
.embed {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.embed.frame {
  flex: 1;
  min-height: 0;
  height: 100%;
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
  flex-shrink: 0;
}
.muted {
  color: var(--muted);
}
.pane {
  flex: 1;
  min-height: 0;
  width: 100%;
  border: 0;
  border-radius: 8px;
  background: #fff;
}
</style>
