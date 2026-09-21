<template>
  <div class="page" v-if="table">
    <PageHeader :title="`${table.name} · 版本`" :subtitle="`已发布 v${current}。对比两版可看结构变化语句。把历史写回当前表会变成草稿，需再发布。`">
      <template #actions>
        <a-button @click="back">返回表明细</a-button>
      </template>
    </PageHeader>

    <a-empty v-if="!rows.length" class="card" description="还没有版本记录" />
    <a-table
      v-else
      :data-source="rows"
      :columns="cols"
      row-key="id"
      size="small"
      :pagination="false"
      class="card card-flush mb"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'ver'">
          v{{ record.version }}
          <a-tag v-if="record.version === current" color="green">当前</a-tag>
        </template>
        <template v-else-if="column.key === 'act'">
          <a @click="view = record">查看</a>
          <a
            v-if="canWriteModel && record.version !== current"
            class="ml"
            @click="askRestore(record)"
          >切换为当前</a>
        </template>
      </template>
    </a-table>

    <div class="card">
      <h3>版本对比</h3>
      <div class="filter-bar">
        <span>从</span>
        <a-select v-model:value="leftId" style="width: 220px" :options="verOpts" />
        <span>到</span>
        <a-select v-model:value="rightId" style="width: 220px" :options="verOpts" />
      </div>
      <div v-if="!diff" class="muted">请选择两个版本。</div>
      <template v-else>
        <p v-if="emptyDiff" class="muted">这两版结构相同。</p>
        <a-table
          v-if="diff.meta.length"
          :data-source="diff.meta"
          :columns="metaCols"
          size="small"
          :pagination="false"
          row-key="field"
          class="mb"
        />
        <p v-if="diff.added.length"><b>新增字段</b> {{ diff.added.map((c) => c.name).join('、') }}</p>
        <p v-if="diff.removed.length"><b>删除字段</b> {{ diff.removed.map((c) => c.name).join('、') }}</p>
        <a-table
          v-if="diff.changed.length"
          :data-source="diff.changed"
          :columns="chgCols"
          size="small"
          :pagination="false"
          row-key="name"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'chg'">
              <div v-for="c in record.changes" :key="c.field">{{ c.field }}：{{ c.from }} → {{ c.to }}</div>
            </template>
          </template>
        </a-table>
        <div class="sql-h">
          <h3>结构变化语句</h3>
        </div>
        <p class="muted">从 v{{ left?.version }} → v{{ right?.version }}，由两版快照生成的逻辑 DDL（添加 / 修改 / 删除字段）。不按引擎拆方言，不执行。</p>
        <SqlBlock :text="alterSql" />
      </template>
    </div>

    <a-modal v-model:open="showView" :title="view ? `v${view.version} 快照` : '快照'" :footer="null" width="720px">
      <template v-if="view">
        <p class="muted">{{ view.snapshot.comment }} · {{ view.snapshot.domain || '未归属' }} · {{ view.createdAt }}</p>
        <a-table :data-source="view.snapshot.columns" :columns="snapCols" size="small" :pagination="false" row-key="name">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'logic'">{{ record.logic?.desc || record.logic?.kind || '—' }}</template>
          </template>
        </a-table>
      </template>
    </a-modal>
  </div>
  <div v-else class="page">
    <p class="muted">表不存在。</p>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import PageHeader from '../../components/PageHeader.vue';
import SqlBlock from '../../components/SqlBlock.vue';
import { layerHref, parseLayerParam } from '../../config/layers';
import { assessImpact, diffSnapshots, renderAlterSql } from '@dw-ai/engine';
import { confirmImpact } from '../../components/confirmImpact';
import { canWriteModel, projectTables, restoreTableVersion, tableVersionsOf } from '../../stores/app';
import type { TableVersion } from '../../types';

const route = useRoute();
const router = useRouter();
const layer = computed(() => parseLayerParam(route.params.layer));
const domain = computed(() => String(route.params.domain ?? '_none'));
const table = computed(() => projectTables.value.find((t) => t.id === route.params.tableId));
const rows = computed(() => (table.value ? tableVersionsOf(table.value.id) : []));
const current = computed(() => rows.value[0]?.version ?? 0);
const view = ref<TableVersion | null>(null);
const showView = computed({
  get: () => Boolean(view.value),
  set: (v) => {
    if (!v) view.value = null;
  },
});
const leftId = ref('');
const rightId = ref('');

const verOpts = computed(() =>
  rows.value.map((v) => ({ value: v.id, label: `v${v.version} · ${v.note} · ${v.createdAt}` }))
);

watch(
  rows,
  (list) => {
    leftId.value = list[1]?.id ?? list[0]?.id ?? '';
    rightId.value = list[0]?.id ?? '';
  },
  { immediate: true }
);

const left = computed(() => rows.value.find((v) => v.id === leftId.value));
const right = computed(() => rows.value.find((v) => v.id === rightId.value));
const diff = computed(() =>
  left.value && right.value ? diffSnapshots(left.value.snapshot, right.value.snapshot) : null
);
const emptyDiff = computed(
  () => diff.value && !diff.value.meta.length && !diff.value.added.length && !diff.value.removed.length && !diff.value.changed.length
);
const alterSql = computed(() => {
  if (!left.value || !right.value) return '';
  const body = renderAlterSql(left.value.snapshot, right.value.snapshot);
  return `-- v${left.value.version} → v${right.value.version}\n${body}`;
});

const cols = [
  { title: '版本', key: 'ver', width: 120 },
  { title: '说明', dataIndex: 'note' },
  { title: '操作人', dataIndex: 'createdBy', width: 100 },
  { title: '时间', dataIndex: 'createdAt', width: 160 },
  { title: '操作', key: 'act', width: 160 },
];
const metaCols = [
  { title: '属性', dataIndex: 'field', width: 120 },
  { title: '从', dataIndex: 'from' },
  { title: '到', dataIndex: 'to' },
];
const chgCols = [
  { title: '字段', dataIndex: 'name', width: 160 },
  { title: '变化', key: 'chg' },
];
const snapCols = [
  { title: '字段', dataIndex: 'name', width: 160 },
  { title: '类型', dataIndex: 'type', width: 140 },
  { title: '注释', dataIndex: 'comment' },
  { title: '口径', key: 'logic' },
];

function askRestore(record: TableVersion) {
  if (!table.value) return;
  const next = { ...table.value, ...record.snapshot };
  const report = assessImpact(table.value, next, projectTables.value);
  confirmImpact(report.needConfirm ? report : { ...report, needConfirm: true, title: '写回为草稿？发布后才会生成新版本', lines: report.lines }, () => {
    restoreTableVersion(table.value!.id, record.id);
  });
}

function back() {
  router.push(`${layerHref(layer.value)}/${domain.value}/${route.params.tableId}`);
}
</script>

<style scoped>
h3 {
  margin: 0 0 12px;
  font-size: 14px;
}
.mb {
  margin-bottom: 12px;
}
.ml {
  margin-left: 10px;
}
.sql-h h3 {
  margin: 16px 0 6px;
  font-size: 14px;
}
p {
  margin: 0 0 8px;
  font-size: 13px;
}
</style>
