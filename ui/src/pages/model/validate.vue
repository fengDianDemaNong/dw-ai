<template>
  <div class="page">
    <PageHeader title="规范校验" subtitle="对项目内已有表做命名与词根检查，只读，不改表。发布草案时会再跑同一套检查。" />

    <div class="filter-bar">
      <a-input v-model:value="name" placeholder="输入表名，例如 dwd_trd_order_item_di" style="width: 360px" @pressEnter="check" />
      <a-select v-model:value="layer" allow-clear placeholder="声明分层" style="width: 140px" :options="layers" />
      <a-button type="primary" @click="check">校验</a-button>
    </div>

    <div v-if="issues.length" class="card mb">
      <div v-for="(iss, i) in issues" :key="i" class="row">
        <a-tag :color="iss.level === 'error' ? 'red' : iss.level === 'warn' ? 'orange' : 'green'">{{ iss.level }}</a-tag>
        <span>{{ iss.rule }} · {{ iss.message }}</span>
      </div>
    </div>

    <div class="card">
      <h3>项目内已有表</h3>
      <a-table :data-source="checkedTables" :columns="cols" size="small" :pagination="false" row-key="id">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'ok'">
            <a-tag :color="record.ok ? 'green' : 'red'">{{ record.ok ? '通过' : '不通过' }}</a-tag>
          </template>
          <template v-else-if="column.key === 'msg'">
            {{ record.msg }}
          </template>
        </template>
      </a-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import PageHeader from '../../components/PageHeader.vue';
import { validateFieldName, validateTableName } from '../../engine/naming';
import { projectLayerRules, projectRoots, projectTables } from '../../stores/app';
import type { SpecIssue, WarehouseTable } from '../../types';

const name = ref('dwd_trd_order_item_di');
const layer = ref<string | undefined>('DWD');
const issues = ref<SpecIssue[]>([]);
const layers = computed(() => projectLayerRules.value.map((r) => ({ value: r.layer, label: r.layer })));
const knownRoots = computed(() => projectRoots.value.map((r) => r.code));

function checkTable(t: Pick<WarehouseTable, 'name' | 'layer' | 'columns'>): SpecIssue[] {
  return [
    ...validateTableName(t.name, t.layer),
    ...t.columns.flatMap((c) => validateFieldName(c.name, knownRoots.value)),
  ];
}

function check() {
  const t = projectTables.value.find((x) => x.name === name.value);
  issues.value = t
    ? checkTable(t)
    : validateTableName(name.value, layer.value);
}

const checkedTables = computed(() =>
  projectTables.value.map((t) => {
    const iss = checkTable(t);
    const err = iss.find((i) => i.level === 'error');
    const warn = iss.find((i) => i.level === 'warn');
    return {
      ...t,
      ok: !err,
      msg: err?.message ?? warn?.message ?? iss.find((i) => i.level === 'info')?.message ?? '—',
    };
  })
);

const cols = [
  { title: '分层', dataIndex: 'layer', width: 80 },
  { title: '表名', dataIndex: 'name' },
  { title: '校验', key: 'ok', width: 100 },
  { title: '说明', key: 'msg' },
];
</script>

<style scoped>
h3 { margin: 0 0 10px; font-size: 14px; }
.mb { margin-bottom: 12px; }
.row { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; }
</style>
