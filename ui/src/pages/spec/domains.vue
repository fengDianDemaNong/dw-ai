<template>
  <div class="page">
    <PageHeader title="主题域" subtitle="按业务过程划分，不按部门划分。域之间通过标准维度关联。">
      <template #actions>
        <a-button @click="router.push('/w/spec/io')">导入导出</a-button>
        <a-button @click="router.push('/w/spec/copilot')">AI 设计</a-button>
        <a-button v-if="canWrite" type="primary" @click="openCreate">新建主题域</a-button>
      </template>
    </PageHeader>
    <SpecReadonlyTip />

    <a-table :columns="cols" :data-source="domains" row-key="id" :pagination="false" size="small" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'owners'">
          {{ record.bizOwner }}（业务）/ {{ record.techOwner }}（技术）
        </template>
        <template v-else-if="column.key === 'related'">
          <a-tag v-for="r in record.related" :key="r">{{ r }}</a-tag>
          <span v-if="!record.related.length" class="muted">—</span>
        </template>
        <template v-else-if="column.key === 'entities'">
          {{ record.coreEntities.filter((e: string) => /[\u4e00-\u9fa5]/.test(e)).join('、') || record.coreEntities.join('、') }}
        </template>
        <template v-else-if="column.key === 'action'">
          <template v-if="canWrite">
            <a @click="openEdit(record)">编辑</a>
            <a-popconfirm title="确定删除该主题域？仍被表引用时无法删除。" @confirm="removeDomain(record.id)">
              <a class="danger">删除</a>
            </a-popconfirm>
          </template>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="open" :title="editingId ? '编辑主题域' : '新建主题域'" ok-text="保存" @ok="save">
      <a-form layout="vertical">
        <a-form-item label="编码（英文缩写，项目内唯一）" required>
          <a-input v-model:value="form.code" placeholder="TRD" :disabled="Boolean(editingId)" />
        </a-form-item>
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="交易域" />
        </a-form-item>
        <a-form-item label="业务定义">
          <a-textarea v-model:value="form.definition" :rows="2" />
        </a-form-item>
        <a-form-item label="核心实体（逗号分隔）">
          <a-input v-model:value="form.entities" placeholder="订单,支付,退款" />
        </a-form-item>
        <a-form-item label="关联主题域">
          <a-select
            v-model:value="form.related"
            mode="multiple"
            allow-clear
            placeholder="通过标准维度关联的上下游域"
            :options="relatedOptions"
          />
        </a-form-item>
        <a-form-item label="业务 Owner / 技术 Owner / 数据 Owner">
          <a-input-group compact>
            <a-input v-model:value="form.bizOwner" style="width: 33%" placeholder="业务" />
            <a-input v-model:value="form.techOwner" style="width: 33%" placeholder="技术" />
            <a-input v-model:value="form.dataOwner" style="width: 34%" placeholder="数据" />
          </a-input-group>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { message } from 'ant-design-vue';
import { useRouter } from 'vue-router';
import PageHeader from '../../components/PageHeader.vue';
import SpecReadonlyTip from '../../components/SpecReadonlyTip.vue';
import { addDomain, can, projectDomains, removeDomain, updateDomain } from '../../stores/app';
import type { Domain } from '../../types';

const router = useRouter();
const domains = projectDomains;
const canWrite = computed(() => can('spec:write'));
const open = ref(false);
const editingId = ref<string | null>(null);
const form = reactive({
  code: '',
  name: '',
  definition: '',
  entities: '',
  related: [] as string[],
  bizOwner: '张三',
  techOwner: '李四',
  dataOwner: '王五',
});

const relatedOptions = computed(() =>
  domains.value
    .filter((d) => d.id !== editingId.value)
    .map((d) => ({ value: d.code, label: `${d.code} ${d.name}` }))
);

const cols = computed(() => [
  { title: '编码', dataIndex: 'code', width: 90 },
  { title: '名称', dataIndex: 'name', width: 110 },
  { title: '业务定义', dataIndex: 'definition' },
  { title: '核心实体', key: 'entities', width: 160 },
  { title: '负责人', key: 'owners', width: 220 },
  { title: '数据 Owner', dataIndex: 'dataOwner', width: 100 },
  { title: '关联域', key: 'related', width: 120 },
  ...(canWrite.value ? [{ title: '操作', key: 'action', width: 100 }] : []),
]);

function resetForm() {
  form.code = '';
  form.name = '';
  form.definition = '';
  form.entities = '';
  form.related = [];
  form.bizOwner = '张三';
  form.techOwner = '李四';
  form.dataOwner = '王五';
}

function openCreate() {
  editingId.value = null;
  resetForm();
  open.value = true;
}

function openEdit(record: Domain) {
  editingId.value = record.id;
  form.code = record.code;
  form.name = record.name;
  form.definition = record.definition;
  form.entities = record.coreEntities.join('、');
  form.related = [...record.related];
  form.bizOwner = record.bizOwner;
  form.techOwner = record.techOwner;
  form.dataOwner = record.dataOwner;
  open.value = true;
}

function payload() {
  return {
    code: form.code,
    name: form.name,
    definition: form.definition,
    bizOwner: form.bizOwner,
    techOwner: form.techOwner,
    dataOwner: form.dataOwner,
    related: form.related,
    coreEntities: form.entities.split(/[,，、]/).map((s) => s.trim()).filter(Boolean),
  };
}

function save() {
  if (!form.code || !form.name) {
    message.warning('编码和名称必填');
    return;
  }
  const ok = editingId.value ? updateDomain(editingId.value, payload()) : addDomain(payload());
  if (ok) open.value = false;
}
</script>
