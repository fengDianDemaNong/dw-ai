<template>
  <div class="page">
    <PageHeader title="影响与血缘" subtitle="边来自表的来源声明和字段加工，不是手动画的。点表看字段引用。" />
    <div class="card mb">
      <div class="lane" v-for="layer in layers" :key="layer">
        <div class="lab">{{ layer }}</div>
        <div class="nodes">
          <button
            v-for="t in byLayer(layer)"
            :key="t.id"
            class="node"
            :class="{ on: focus === t.id }"
            @click="focus = t.id"
          >
            <b>{{ t.name }}</b>
            <span>{{ sourceLabel(t) }}</span>
          </button>
          <div v-if="!byLayer(layer).length" class="empty">暂无</div>
        </div>
      </div>
    </div>
    <div class="card" v-if="focusTable">
      <h3>{{ focusTable.name }}</h3>
      <p class="muted">{{ focusTable.comment }} · 粒度 {{ focusTable.grain || '—' }}</p>
      <div class="cols">
        <div>
          <h4>上游</h4>
          <p v-if="!upstreams.length" class="muted">无来源</p>
          <div v-for="u in upstreams" :key="u.id">
            <a @click="focus = u.id">{{ u.name }}</a>
          </div>
        </div>
        <div>
          <h4>下游</h4>
          <p v-if="!downstreams.length" class="muted">无下游</p>
          <div v-for="d in downstreams" :key="d.id">
            <a @click="focus = d.id">{{ d.name }}</a>
          </div>
        </div>
      </div>
      <a-table
        class="mt"
        :data-source="focusTable.columns"
        :columns="cols"
        size="small"
        :pagination="false"
        row-key="name"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'logic'">{{ logicSummary(record) }}</template>
        </template>
      </a-table>
      <a-button class="mt" @click="goDetail">打开表明细</a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { logicSummary, resolvedSources, tableDependentsOf } from '@dw-ai/engine';
import PageHeader from '../../components/PageHeader.vue';
import { layerHref } from '../../config/layers';
import { projectLayerRules, projectTables } from '../../stores/app';

const router = useRouter();
const layers = computed(() => projectLayerRules.value.map((r) => r.layer));
const focus = ref(projectTables.value.find((t) => t.layer === 'DWS')?.id ?? projectTables.value[0]?.id ?? '');
const focusTable = computed(() => projectTables.value.find((t) => t.id === focus.value));

function byLayer(layer: string) {
  return projectTables.value.filter((t) => t.layer === layer);
}
function sourceLabel(t: (typeof projectTables.value)[0]) {
  const srcs = resolvedSources(t)
    .map((s) => projectTables.value.find((x) => x.id === s.tableId)?.name ?? s.tableId)
    .join(' + ');
  return srcs || '无来源';
}
const upstreams = computed(() => {
  const t = focusTable.value;
  if (!t) return [];
  return resolvedSources(t)
    .map((s) => projectTables.value.find((x) => x.id === s.tableId))
    .filter((x): x is NonNullable<typeof x> => Boolean(x));
});
const downstreams = computed(() =>
  focusTable.value ? tableDependentsOf(focusTable.value.id, projectTables.value) : []
);
const cols = [
  { title: '字段', dataIndex: 'name', width: 160 },
  { title: '加工', key: 'logic' },
];
function goDetail() {
  const t = focusTable.value;
  if (!t) return;
  router.push(`${layerHref(t.layer)}/${encodeURIComponent(t.domain || '_none')}/${t.id}`);
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
  text-align: left;
  cursor: pointer;
}
.node.on { border-color: #0e7490; background: #ecfeff; }
.node b { display: block; font-size: 12px; }
.node span, .empty { font-size: 12px; color: #64748b; }
.mb { margin-bottom: 12px; }
.mt { margin-top: 12px; }
h3, h4 { margin: 0 0 8px; font-size: 14px; }
.cols { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
</style>
