<template>
  <div class="page" v-if="table">
    <PageHeader :title="table.name" :subtitle="table.comment || `${layer} 表明细`">
      <template #actions>
        <a-button @click="back">返回列表</a-button>
        <a-button @click="goVersions">版本</a-button>
        <a-tooltip :title="hasAiCap('model_design') ? '' : '本组织未开通此项'">
          <a-button :disabled="!hasAiCap('model_design')" @click="goAi">AI 改这张表</a-button>
        </a-tooltip>
        <a-button v-if="canWrite" @click="editing = true">编辑</a-button>
        <a-popconfirm v-if="canWrite" :title="deleteConfirmTitle" @confirm="onRemove">
          <a-button danger>删除</a-button>
        </a-popconfirm>
      </template>
    </PageHeader>

    <div class="stat-grid">
      <div class="stat">
        <div class="k">主题域</div>
        <div class="v" style="font-size: 18px">{{ table.domain || '未归属' }}</div>
      </div>
      <div class="stat">
        <div class="k">粒度</div>
        <div class="v" style="font-size: 18px">{{ table.grain || '—' }}</div>
      </div>
      <div class="stat">
        <div class="k">周期 / 分区</div>
        <div class="v" style="font-size: 18px">{{ table.period || '—' }} / {{ table.partition || '—' }}</div>
      </div>
      <div class="stat">
        <div class="k">数据等级</div>
        <div class="v" style="font-size: 16px"><GradeTag :code="table.grade" policy /></div>
      </div>
      <div class="stat">
        <div class="k">状态</div>
        <div class="v" style="font-size: 18px">{{ TABLE_STATUS_LABEL[table.status] || table.status }}</div>
      </div>
      <div class="stat">
        <div class="k">来源表</div>
        <div class="v" style="font-size: 16px">{{ sourceName || '—' }}</div>
      </div>
    </div>

    <div class="card mb">
      <h3>字段</h3>
      <a-table :data-source="table.columns" :columns="colCols" size="small" :pagination="false" row-key="name">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'null'">
            {{ record.nullable === false ? '否' : '是' }}
          </template>
          <template v-else-if="column.key === 'default'">
            <code v-if="record.nullable === false && record.defaultValue">{{ record.defaultValue }}</code>
            <span v-else class="muted">—</span>
          </template>
          <template v-else-if="column.key === 'grade'">
            <GradeTag :code="record.grade || table.grade" />
          </template>
          <template v-else-if="column.key === 'enum'">
            <span v-if="record.enumValues?.length">{{ record.enumValues.join(', ') }}</span>
            <span v-else class="muted">—</span>
          </template>
          <template v-else-if="column.key === 'sens'">
            <a-tag v-if="record.sensitive" color="red">敏感</a-tag>
            <span v-else class="muted">—</span>
          </template>
        </template>
      </a-table>
    </div>

    <div class="card">
      <DdlPreview :spec="ddlSpec" title="DDL" />
    </div>

    <TableFormModal :open="editing" :table="table" :layer="layer" @close="editing = false" />
  </div>
  <div v-else class="page">
    <p class="muted">表不存在或已删除。</p>
    <a-button @click="back">返回</a-button>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { specFromTable } from '@dw-ai/engine';
import PageHeader from '../../components/PageHeader.vue';
import DdlPreview from '../../components/DdlPreview.vue';
import TableFormModal from '../../components/TableFormModal.vue';
import GradeTag from '../../components/GradeTag.vue';
import { layerAiHref, layerHref, layerVersionsHref, parseLayerParam, TABLE_STATUS_LABEL } from '../../config/layers';
import { can, hasAiCap, projectGrades, projectTables, removeTable, tableDependents } from '../../stores/app';

const route = useRoute();
const router = useRouter();
const canWrite = computed(() => can('model:write'));
const editing = ref(false);
const layer = computed(() => parseLayerParam(route.params.layer));
const domain = computed(() => String(route.params.domain ?? '_none'));
const table = computed(() => projectTables.value.find((t) => t.id === route.params.tableId));
const sourceName = computed(() => {
  const id = table.value?.createdFrom;
  if (!id) return '';
  return projectTables.value.find((t) => t.id === id)?.name || id;
});

const colCols = [
  { title: '字段', dataIndex: 'name', width: 180 },
  { title: '类型', dataIndex: 'type', width: 140 },
  { title: '注释', dataIndex: 'comment' },
  { title: '等级', key: 'grade', width: 140 },
  { title: '可空', key: 'null', width: 70 },
  { title: '默认值', key: 'default', width: 100 },
  { title: '枚举', key: 'enum', width: 140 },
  { title: '敏感', key: 'sens', width: 80 },
];

const ddlSpec = computed(() =>
  table.value ? specFromTable(table.value, { grades: projectGrades.value }) : null
);

const deleteConfirmTitle = computed(() => {
  if (!table.value) return '确定删除该表？';
  const n = tableDependents(table.value.id).length;
  return n ? `确定删除该表？有 ${n} 个下游引用` : '确定删除该表？';
});

function back() {
  router.push(`${layerHref(layer.value)}/${domain.value}`);
}
function goVersions() {
  if (!table.value) return;
  router.push(layerVersionsHref(layer.value, domain.value, table.value.id));
}
function goAi() {
  if (!table.value) return;
  router.push(layerAiHref(layer.value, table.value.id));
}
function onRemove() {
  if (!table.value) return;
  removeTable(table.value.id);
  back();
}
</script>

<style scoped>
h3 {
  margin: 0 0 10px;
  font-size: 14px;
}
.mb {
  margin-bottom: 12px;
}
</style>
