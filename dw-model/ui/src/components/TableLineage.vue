<template>
  <div>
    <div class="flow">
      <div class="col">
        <h4>上游</h4>
        <p v-if="!upstreams.length" class="muted">无</p>
        <button v-for="u in upstreams" :key="u.id" class="node" type="button" @click="$emit('open', u)">
          <b>{{ u.name }}</b>
          <span>{{ u.layer }}</span>
        </button>
      </div>
      <div class="arr">→</div>
      <div class="col">
        <h4>本表</h4>
        <div class="node self">
          <b>{{ table.name }}</b>
          <span>{{ table.layer }} · {{ table.columns.length }} 字段</span>
        </div>
      </div>
      <div class="arr">→</div>
      <div class="col">
        <h4>下游</h4>
        <p v-if="!downstreams.length" class="muted">无</p>
        <button v-for="d in downstreams" :key="d.id" class="node" type="button" @click="$emit('open', d)">
          <b>{{ d.name }}</b>
          <span>{{ d.layer }}</span>
        </button>
      </div>
    </div>
    <a-table
      class="mt"
      :data-source="fieldRows"
      :columns="cols"
      size="small"
      :pagination="false"
      row-key="name"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'up'">
          <span v-if="!record.up.length" class="muted">—</span>
          <span v-else>{{ record.up.join('、') }}</span>
        </template>
        <template v-else-if="column.key === 'down'">
          <span v-if="!record.down.length" class="muted">—</span>
          <div v-else>
            <div v-for="(x, i) in record.down" :key="i">{{ x }}</div>
          </div>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { fieldDependents, resolvedSources } from '@dw-ai/engine';
import type { WarehouseTable } from '../types';

const props = defineProps<{
  table: WarehouseTable;
  catalog: WarehouseTable[];
}>();
defineEmits<{ open: [WarehouseTable] }>();

const upstreams = computed(() =>
  resolvedSources(props.table)
    .map((s) => props.catalog.find((t) => t.id === s.tableId))
    .filter((x): x is WarehouseTable => Boolean(x))
);
const downstreams = computed(() =>
  props.catalog.filter((t) => t.id !== props.table.id && resolvedSources(t).some((s) => s.tableId === props.table.id))
);
const fieldRows = computed(() =>
  props.table.columns.map((c) => ({
    name: c.name,
    up: (c.logic?.sources ?? []).map((s) => `${s.alias}.${s.column}`),
    down: fieldDependents(props.table.id, c.name, props.catalog)
      .filter((h) => h.field)
      .map((h) => `${h.tableName}.${h.field}`),
  }))
);
const cols = [
  { title: '本表字段', dataIndex: 'name', width: 160 },
  { title: '上游列', key: 'up' },
  { title: '下游表.字段', key: 'down' },
];
</script>

<style scoped>
.flow {
  display: grid;
  grid-template-columns: 1fr auto 1fr auto 1fr;
  gap: 8px;
  align-items: start;
  margin-bottom: 12px;
}
.col h4 { margin: 0 0 8px; font-size: 13px; }
.arr { padding-top: 28px; color: #64748b; font-weight: 700; }
.node {
  display: block;
  width: 100%;
  text-align: left;
  margin-bottom: 6px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #f8fafc;
  cursor: pointer;
}
.node.self { cursor: default; border-color: #0e7490; background: #ecfeff; }
.node b { display: block; font-size: 12px; }
.node span { font-size: 12px; color: #64748b; }
.mt { margin-top: 8px; }
</style>
