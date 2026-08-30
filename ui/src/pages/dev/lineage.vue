<template>
  <div class="page">
    <PageHeader title="血缘追踪" subtitle="跨层依赖：DWS 任务等待 DWD 完成。物化表上线后会接到同一条链路上。" />
    <div class="card">
      <div class="lane" v-for="layer in layers" :key="layer">
        <div class="lab">{{ layer }}</div>
        <div class="nodes">
          <div v-for="t in byLayer(layer)" :key="t.id" class="node">
            <b>{{ t.name }}</b>
            <span>{{ t.comment }}</span>
          </div>
          <div v-if="!byLayer(layer).length" class="empty">暂无</div>
        </div>
      </div>
    </div>
    <div class="card mt">
      <h3>任务 DAG</h3>
      <div v-for="j in jobs" :key="j.id" class="dag">
        <span class="muted">{{ j.dependsOn.length ? j.dependsOn.join(', ') : '∅' }}</span>
        <span>→</span>
        <b>{{ j.name }}</b>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import PageHeader from '../../components/PageHeader.vue';
import { projectJobs, projectLayerRules, projectTables } from '../../stores/app';

const layers = computed(() => projectLayerRules.value.map((r) => r.layer));
const tables = projectTables;
const jobs = projectJobs;
function byLayer(layer: string) {
  return tables.value.filter((t) => t.layer === layer);
}
</script>

<style scoped>
.lane { display: flex; gap: 12px; margin-bottom: 14px; align-items: flex-start; }
.lab { width: 48px; font-weight: 650; color: #0e7490; padding-top: 8px; }
.nodes { display: flex; gap: 8px; flex-wrap: wrap; flex: 1; }
.node {
  background: #f8fafc;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 8px 10px;
  min-width: 180px;
}
.node b { display: block; font-size: 12px; }
.node span, .empty { font-size: 12px; color: #64748b; }
.mt { margin-top: 12px; }
h3 { margin: 0 0 10px; font-size: 14px; }
.dag { display: flex; gap: 8px; margin-bottom: 6px; font-size: 13px; }
</style>
