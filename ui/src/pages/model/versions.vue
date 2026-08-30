<template>
  <div class="page" v-if="table">
    <PageHeader :title="`${table.name} · 版本`" :subtitle="`当前 v${current}。对比两版，或把历史结构切换为当前（会再记一版）。`">
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
          <a-popconfirm
            v-if="canWriteModel && record.version !== current"
            title="把该版结构写回当前表，并生成新版本？"
            @confirm="onRestore(record.id)"
          >
            <a class="ml">切换为当前</a>
          </a-popconfirm>
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
        <p v-if="diff.added.length"><b>新增字段</b> {{ colNames(diff.added) }}</p>
        <p v-if="diff.removed.length"><b>删除字段</b> {{ colNames(diff.removed) }}</p>
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
              <div>字段有变化</div>
            </template>
          </template>
        </a-table>
      </template>
    </div>

    <a-modal v-model:open="showView" :title="view ? `v${view.version} 快照` : '快照'" :footer="null" width="720px">
      <template v-if="view">
        <p class="muted">{{ snapComment(view.snapshot) }} · {{ view.createdAt }}</p>
        <a-table :data-source="snapColsOf(view.snapshot)" :columns="snapCols" size="small" :pagination="false" row-key="name" />
      </template>
    </a-modal>
  </div>
  <div v-else class="page">
    <p class="muted">表不存在。</p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api, type TableVersion } from '../../api/client';
import PageHeader from '../../components/PageHeader.vue';
import { layerHref, parseLayerParam } from '../../config/layers';
import { app, canWriteModel, projectTables, restoreTableVersion } from '../../stores/app';

const route = useRoute();
const router = useRouter();
const layer = computed(() => parseLayerParam(route.params.layer));
const domain = computed(() => String(route.params.domain ?? '_none'));
const table = computed(() => projectTables.value.find((t) => t.id === route.params.tableId));
const rows = ref<TableVersion[]>([]);
const current = computed(() => rows.value[0]?.version ?? table.value?.currentVersion ?? 0);
const view = ref<TableVersion | null>(null);
const showView = computed({
  get: () => Boolean(view.value),
  set: (v) => {
    if (!v) view.value = null;
  },
});
const leftId = ref('');
const rightId = ref('');
const diff = ref<{
  meta: { field: string; from: string; to: string }[];
  added: Record<string, unknown>[];
  removed: Record<string, unknown>[];
  changed: { name: string; from: Record<string, unknown>; to: Record<string, unknown> }[];
} | null>(null);

const verOpts = computed(() =>
  rows.value.map((v) => ({ value: v.id, label: `v${v.version} · ${v.note} · ${v.createdAt}` }))
);
const emptyDiff = computed(
  () =>
    diff.value &&
    !diff.value.meta.length &&
    !diff.value.added.length &&
    !diff.value.removed.length &&
    !diff.value.changed.length
);

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
];

function colNames(list: Record<string, unknown>[]) {
  return list.map((c) => String(c.name ?? '')).filter(Boolean).join('、');
}

function snapComment(snap: Record<string, unknown>) {
  return String(snap.comment ?? snap.domain ?? '—');
}

function snapColsOf(snap: Record<string, unknown>) {
  return Array.isArray(snap.columns) ? (snap.columns as Record<string, unknown>[]) : [];
}

async function reload() {
  if (!app.currentProjectId || !table.value) return;
  rows.value = await api.versions.list(app.currentProjectId, table.value.id);
}

async function loadDiff() {
  if (!app.currentProjectId || !table.value || !leftId.value || !rightId.value) {
    diff.value = null;
    return;
  }
  diff.value = await api.versions.diff(app.currentProjectId, table.value.id, leftId.value, rightId.value);
}

async function onRestore(versionId: string) {
  if (!table.value) return;
  await restoreTableVersion(table.value.id, versionId);
  await reload();
}

function back() {
  router.push(`${layerHref(layer.value)}/${domain.value}/${route.params.tableId}`);
}

watch(
  rows,
  (list) => {
    leftId.value = list[1]?.id ?? list[0]?.id ?? '';
    rightId.value = list[0]?.id ?? '';
  },
  { immediate: true }
);

watch([leftId, rightId], () => {
  void loadDiff();
});

onMounted(() => {
  void reload();
});
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
p {
  margin: 0 0 8px;
  font-size: 13px;
}
</style>
