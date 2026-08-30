<template>
  <div class="page">
    <PageHeader
      title="分层规范"
      subtitle="每一层的字段格式、脱敏、空值处理都在这里设定。建模中心按这些规则加工。"
    >
      <template #actions>
        <a-button @click="router.push('/w/spec/io')">导入导出</a-button>
        <a-button @click="router.push('/w/spec/copilot')">AI 设计分层</a-button>
        <a-button v-if="canWriteSpec" type="primary" @click="openCreate">新增分层</a-button>
      </template>
    </PageHeader>
    <SpecReadonlyTip />
    <a-table :columns="cols" :data-source="rows" row-key="layer" :pagination="false" size="small" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'serve'">
          <a-tag v-if="record.serve === 'forbid'" color="red">禁止</a-tag>
          <a-tag v-else-if="record.serve === 'approval'" color="orange">审批后</a-tag>
          <a-tag v-else color="green">允许</a-tag>
        </template>
        <template v-else-if="column.key === 'naming'">
          <code>{{ record.naming }}</code>
        </template>
        <template v-else-if="column.key === 'masking'">
          <a-tag>{{ maskingLabel(record.masking) }}</a-tag>
        </template>
        <template v-else-if="column.key === 'nulls'">
          <a-tag>{{ nullLabel(record.nullHandling) }}</a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <a @click="openEdit(record)">{{ canWriteSpec ? '规则设定' : '查看规则' }}</a>
          <a-popconfirm v-if="canWriteSpec" :title="`确定删除分层 ${record.layer}？`" @confirm="removeLayer(record.layer)">
            <a class="danger">删除</a>
          </a-popconfirm>
        </template>
      </template>
    </a-table>

    <a-drawer
      :open="open"
      :title="editingLayer ? `${form.layer} 规则设定` : '新增分层'"
      width="640"
      @close="open = false"
    >
      <a-form layout="vertical" :disabled="!canWriteSpec">
        <h4>分层基础</h4>
        <a-form-item label="层级编码" required>
          <a-auto-complete
            v-if="!editingLayer"
            v-model:value="form.layer"
            :options="layerOptions"
            placeholder="ODS / DWD / DWS / ADS，或输入自定义如 DIM"
            style="width: 100%"
            @change="applyDefaults"
          />
          <a-input v-else v-model:value="form.layer" disabled />
        </a-form-item>
        <a-form-item label="表命名规则" required>
          <a-input v-model:value="form.naming" placeholder="dwd_[主题域]_[业务过程]_[粒度]_[周期]" />
        </a-form-item>
        <a-form-item label="数据保留" required>
          <a-input v-model:value="form.retention" placeholder="永久 / 7-14天 / 按需" />
        </a-form-item>
        <a-form-item label="对外服务">
          <a-select v-model:value="form.serve" :options="serveOptions" />
        </a-form-item>
        <a-form-item label="说明">
          <a-textarea v-model:value="form.note" :rows="2" />
        </a-form-item>

        <h4>字段格式</h4>
        <a-form-item label="字段命名格式">
          <a-textarea v-model:value="form.fieldFormat" :rows="2" placeholder="[业务过程]_[度量/维度]_[词根]" />
        </a-form-item>
        <a-form-item label="时间格式">
          <a-input v-model:value="form.timeFormat" placeholder="yyyy-MM-dd / yyyy-MM-dd HH:mm:ss" />
        </a-form-item>

        <h4>脱敏</h4>
        <a-form-item label="脱敏策略">
          <a-select v-model:value="form.masking" :options="MASKING_OPTIONS" />
        </a-form-item>
        <a-form-item label="脱敏说明">
          <a-textarea v-model:value="form.maskingNote" :rows="2" placeholder="哪些字段必须脱敏、用什么算法" />
        </a-form-item>

        <h4>空值处理</h4>
        <a-form-item label="空值策略">
          <a-select v-model:value="form.nullHandling" :options="NULL_OPTIONS" />
        </a-form-item>
        <a-form-item label="填充规则" v-if="form.nullHandling === 'fill'">
          <a-input v-model:value="form.nullFill" placeholder="度量 0，维度「未知」" />
        </a-form-item>
      </a-form>
      <template #footer>
        <a-button @click="open = false">{{ canWriteSpec ? '取消' : '关闭' }}</a-button>
        <a-button v-if="canWriteSpec" type="primary" @click="save">保存</a-button>
      </template>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import { useRoute, useRouter } from 'vue-router';
import PageHeader from '../../components/PageHeader.vue';
import SpecReadonlyTip from '../../components/SpecReadonlyTip.vue';
import {
  defaultLayerPolicy,
  hydrateLayerRule,
  MASKING_OPTIONS,
  maskingLabel,
  NULL_OPTIONS,
  nullLabel,
} from '../../config/layerPolicies';
import { addLayer, canWriteSpec, projectLayerRules, removeLayer, updateLayer } from '../../stores/app';
import type { LayerRule, MaskingPolicy, NullPolicy } from '../../types';

const router = useRouter();
const route = useRoute();
const layers = projectLayerRules;
const rows = computed(() => layers.value.map((r) => hydrateLayerRule(r)));
const open = ref(false);
const editingLayer = ref<string | null>(null);
const form = reactive({
  layer: 'ODS',
  naming: '',
  retention: '',
  serve: 'forbid' as LayerRule['serve'],
  note: '',
  fieldFormat: '',
  timeFormat: '',
  masking: 'mask' as MaskingPolicy,
  maskingNote: '',
  nullHandling: 'fill' as NullPolicy,
  nullFill: '',
});

const serveOptions = [
  { value: 'forbid', label: '禁止' },
  { value: 'approval', label: '审批后' },
  { value: 'allow', label: '允许' },
];

const layerOptions = [
  { value: 'ODS', label: 'ODS 原始层' },
  { value: 'DWD', label: 'DWD 明细层' },
  { value: 'DWS', label: 'DWS 汇总层' },
  { value: 'ADS', label: 'ADS 应用层' },
  { value: 'DIM', label: 'DIM 维表层' },
  { value: 'STG', label: 'STG 缓冲层' },
];

const cols = [
  { title: '层级', dataIndex: 'layer', width: 80 },
  { title: '表命名', key: 'naming' },
  { title: '字段格式', dataIndex: 'fieldFormat', ellipsis: true },
  { title: '脱敏', key: 'masking', width: 110 },
  { title: '空值', key: 'nulls', width: 110 },
  { title: '对外', key: 'serve', width: 90 },
  { title: '操作', key: 'action', width: 130 },
];

function fillPolicy(layer: string) {
  const d = defaultLayerPolicy(layer);
  form.fieldFormat = d.fieldFormat;
  form.timeFormat = d.timeFormat;
  form.masking = d.masking;
  form.maskingNote = d.maskingNote;
  form.nullHandling = d.nullHandling;
  form.nullFill = d.nullFill;
}

function applyDefaults() {
  if (editingLayer.value) return;
  fillPolicy(form.layer || 'DIM');
}

function openCreate() {
  editingLayer.value = null;
  form.layer = 'DIM';
  form.naming = 'dim_[主题域]_[维度名]';
  form.retention = '永久';
  form.serve = 'approval';
  form.note = '';
  fillPolicy('DIM');
  open.value = true;
}

function openEdit(record: LayerRule) {
  const r = hydrateLayerRule(record);
  editingLayer.value = r.layer;
  form.layer = r.layer;
  form.naming = r.naming;
  form.retention = r.retention;
  form.serve = r.serve;
  form.note = r.note;
  form.fieldFormat = r.fieldFormat || '';
  form.timeFormat = r.timeFormat || '';
  form.masking = r.masking || 'mask';
  form.maskingNote = r.maskingNote || '';
  form.nullHandling = r.nullHandling || 'fill';
  form.nullFill = r.nullFill || '';
  open.value = true;
}

function payload(): Omit<LayerRule, 'projectId'> {
  return {
    layer: form.layer,
    naming: form.naming,
    retention: form.retention,
    serve: form.serve,
    note: form.note,
    fieldFormat: form.fieldFormat,
    timeFormat: form.timeFormat,
    masking: form.masking,
    maskingNote: form.maskingNote,
    nullHandling: form.nullHandling,
    nullFill: form.nullFill,
  };
}

function save() {
  if (!form.layer || !form.naming || !form.retention) {
    message.warning('层级、表命名规则、数据保留必填');
    return;
  }
  const ok = editingLayer.value ? updateLayer(editingLayer.value, payload()) : addLayer(payload());
  if (ok) open.value = false;
}

watch(
  () => route.query.layer,
  (v) => {
    if (typeof v !== 'string' || !v) return;
    const rec = layers.value.find((l) => l.layer === v.toUpperCase());
    if (rec) openEdit(rec);
  },
  { immediate: true }
);
</script>

<style scoped>
h4 {
  margin: 16px 0 8px;
  font-size: 13px;
  color: #0f766e;
}
h4:first-child {
  margin-top: 0;
}
code {
  font-size: 12px;
}
</style>
