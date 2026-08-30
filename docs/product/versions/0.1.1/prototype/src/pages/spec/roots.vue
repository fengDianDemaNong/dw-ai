<template>
  <div class="page">
    <PageHeader title="词根库" subtitle="AI 生成字段名与度量别名的依据。业务词根按主题域归属。">
      <template #actions>
        <a-button @click="router.push('/w/spec/io')">导入导出</a-button>
        <a-button @click="router.push('/w/spec/copilot')">AI 设计</a-button>
        <a-button v-if="canWriteSpec" type="primary" @click="openCreate">新增词根</a-button>
      </template>
    </PageHeader>
    <SpecReadonlyTip />

    <a-tabs v-model:activeKey="kind">
      <a-tab-pane key="biz" tab="业务词根" />
      <a-tab-pane key="tech" tab="技术词根" />
      <a-tab-pane key="time" tab="时间词根" />
    </a-tabs>

    <a-table :columns="cols" :data-source="rows" row-key="id" :pagination="false" size="small" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'extra'">
          {{ record.formula || record.dataType || record.format || '—' }}
        </template>
        <template v-else-if="column.key === 'action'">
          <template v-if="canWriteSpec">
            <a @click="openEdit(record)">编辑</a>
            <a-popconfirm :title="`确定删除词根 ${record.code}？`" @confirm="removeRoot(record.id)">
              <a class="danger">删除</a>
            </a-popconfirm>
          </template>
          <span v-else class="muted">只读</span>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="open" :title="editingId ? '编辑词根' : '新增词根'" ok-text="保存" @ok="save">
      <a-form layout="vertical">
        <a-form-item label="类型">
          <a-select v-model:value="form.kind" :options="kinds" />
        </a-form-item>
        <a-form-item label="编码" required>
          <a-input v-model:value="form.code" placeholder="gmv" :disabled="Boolean(editingId)" />
        </a-form-item>
        <a-form-item label="中文">
          <a-input v-model:value="form.zh" />
        </a-form-item>
        <a-form-item label="英文">
          <a-input v-model:value="form.en" />
        </a-form-item>
        <a-form-item v-if="form.kind === 'biz'" label="所属域">
          <a-select
            v-model:value="form.domain"
            allow-clear
            placeholder="选择主题域"
            :options="domains.map((d) => ({ value: d.code, label: `${d.code} ${d.name}` }))"
          />
        </a-form-item>
        <a-form-item v-if="form.kind === 'biz'" label="计算公式">
          <a-input v-model:value="form.formula" placeholder="sum(order_amount)" />
        </a-form-item>
        <a-form-item v-if="form.kind === 'tech'" label="数据类型">
          <a-input v-model:value="form.dataType" placeholder="decimal(18,2)" />
        </a-form-item>
        <a-form-item v-if="form.kind === 'time'" label="格式">
          <a-input v-model:value="form.format" placeholder="yyyy-MM-dd" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader.vue';
import SpecReadonlyTip from '../../components/SpecReadonlyTip.vue';
import { addRoot, canWriteSpec, projectDomains, projectRoots, removeRoot, updateRoot } from '../../stores/app';
import type { RootKind, WordRoot } from '../../types';

const router = useRouter();

const kind = ref<RootKind>('biz');
const open = ref(false);
const editingId = ref<string | null>(null);
const domains = projectDomains;
const kinds = [
  { value: 'biz', label: '业务词根' },
  { value: 'tech', label: '技术词根' },
  { value: 'time', label: '时间词根' },
];
const form = reactive({
  kind: 'biz' as RootKind,
  code: '',
  zh: '',
  en: '',
  domain: undefined as string | undefined,
  formula: '',
  dataType: '',
  format: '',
});
const rows = computed(() => projectRoots.value.filter((r) => r.kind === kind.value));
const cols = computed(() => [
  { title: '编码', dataIndex: 'code', width: 120 },
  { title: '中文', dataIndex: 'zh', width: 140 },
  { title: '英文', dataIndex: 'en', width: 140 },
  ...(kind.value === 'biz' ? [{ title: '所属域', dataIndex: 'domain', width: 100 }] : []),
  { title: '公式 / 类型 / 格式', key: 'extra' },
  { title: '操作', key: 'action', width: 100 },
]);

function resetForm() {
  form.kind = kind.value;
  form.code = '';
  form.zh = '';
  form.en = '';
  form.domain = undefined;
  form.formula = '';
  form.dataType = '';
  form.format = '';
}

function openCreate() {
  editingId.value = null;
  resetForm();
  open.value = true;
}

function openEdit(record: WordRoot) {
  editingId.value = record.id;
  form.kind = record.kind;
  form.code = record.code;
  form.zh = record.zh;
  form.en = record.en;
  form.domain = record.domain;
  form.formula = record.formula ?? '';
  form.dataType = record.dataType ?? '';
  form.format = record.format ?? '';
  open.value = true;
}

function payload() {
  return {
    kind: form.kind,
    code: form.code.trim(),
    zh: form.zh,
    en: form.en || form.code.trim(),
    domain: form.kind === 'biz' ? form.domain : undefined,
    formula: form.formula || undefined,
    dataType: form.dataType || undefined,
    format: form.format || undefined,
  };
}

function save() {
  if (!form.code) {
    message.warning('编码必填');
    return;
  }
  const ok = editingId.value ? updateRoot(editingId.value, payload()) : addRoot(payload());
  if (ok) open.value = false;
}
</script>
