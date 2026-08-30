<template>
  <div class="page">
    <PageHeader :title="title" :subtitle="subtitle">
      <template #actions>
        <a-button @click="router.push(layerHref(layer))">返回总览</a-button>
        <a-button @click="router.push(layerAiHref(layer))">AI 设计</a-button>
        <a-button v-if="canWrite" type="primary" @click="openCreate">新增表</a-button>
      </template>
    </PageHeader>

    <a-empty v-if="!tables.length" class="card" :description="`该主题域还没有 ${layer} 表`">
      <a-button v-if="canWrite" type="primary" @click="openCreate">新增表</a-button>
    </a-empty>
    <a-table v-else :columns="cols" :data-source="tables" row-key="id" :pagination="false" size="small" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <a @click="go(record.id)">{{ record.name }}</a>
        </template>
        <template v-else-if="column.key === 'grade'">
          <GradeTag :code="record.grade" policy />
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="record.status === 'published' ? 'green' : record.status === 'deprecated' ? 'default' : 'gold'">
            {{ TABLE_STATUS_LABEL[record.status] || record.status }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'cols'">
          {{ record.columns.length }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a class="ml" @click="router.push(layerAiHref(layer, record.id))">AI</a>
          <template v-if="canWrite">
            <a @click="edit = record">编辑</a>
            <a-popconfirm :title="deleteConfirmTitle(record.id)" @confirm="removeTable(record.id)">
              <a class="danger">删除</a>
            </a-popconfirm>
          </template>
        </template>
      </template>
    </a-table>

    <TableFormModal
      :open="creating || Boolean(edit)"
      :table="edit"
      :layer="layer"
      :default-domain="domainCode === '_none' ? undefined : domainCode"
      @close="creating = false; edit = null"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import PageHeader from '../../components/PageHeader.vue';
import GradeTag from '../../components/GradeTag.vue';
import TableFormModal from '../../components/TableFormModal.vue';
import { layerAiHref, layerHref, parseLayerParam, TABLE_STATUS_LABEL } from '../../config/layers';
import { can, projectDomains, projectTables, removeTable, tableDependents } from '../../stores/app';
import type { WarehouseTable } from '../../types';

const route = useRoute();
const router = useRouter();
const canWrite = computed(() => can('model:write'));
const creating = ref(false);
const edit = ref<WarehouseTable | null>(null);
const layer = computed(() => parseLayerParam(route.params.layer));
const domainCode = computed(() => String(route.params.domain ?? '_none'));
const domain = computed(() => projectDomains.value.find((d) => d.code === domainCode.value));
const title = computed(() =>
  domain.value
    ? `${domain.value.name} · ${layer.value}`
    : domainCode.value === '_none'
      ? `未归属 · ${layer.value}`
      : `${domainCode.value} · ${layer.value}`
);
const subtitle = computed(
  () => domain.value?.definition || `该主题域下的 ${layer.value} 表。点表名看字段详情。`
);
const tables = computed(() =>
  projectTables.value.filter((t) => {
    if (t.layer !== layer.value) return false;
    if (domainCode.value === '_none') return !t.domain;
    return t.domain === domainCode.value;
  })
);

const cols = computed(() => [
  { title: '表名', key: 'name' },
  { title: '说明', dataIndex: 'comment' },
  { title: '等级', key: 'grade', width: 200 },
  { title: '粒度', dataIndex: 'grain', width: 100 },
  { title: '周期', dataIndex: 'period', width: 80 },
  { title: '字段数', key: 'cols', width: 80 },
  { title: '状态', key: 'status', width: 90 },
  { title: '操作', key: 'action', width: 140 },
]);

function deleteConfirmTitle(id: string) {
  const n = tableDependents(id).length;
  return n ? `确定删除该表？有 ${n} 个下游引用` : '确定删除该表？';
}

function openCreate() {
  edit.value = null;
  creating.value = true;
}
function go(id: string) {
  router.push(`${layerHref(layer.value)}/${domainCode.value}/${id}`);
}
</script>

<style scoped>
.ml {
  margin-right: 10px;
}
</style>
