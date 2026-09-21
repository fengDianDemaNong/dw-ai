<template>
  <span v-if="g" class="g">
    <a-tag :color="g.color">{{ g.code }} {{ g.name }}</a-tag>
    <span v-if="policy" class="muted">{{ queryLabel(g.query) }}</span>
  </span>
  <span v-else class="muted">未定级</span>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { queryLabel } from '../config/grades';
import { projectGrades } from '../stores/app';

const props = defineProps<{ code?: string; policy?: boolean }>();
const g = computed(() => projectGrades.value.find((x) => x.code === props.code));
</script>

<style scoped>
.g {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
</style>
