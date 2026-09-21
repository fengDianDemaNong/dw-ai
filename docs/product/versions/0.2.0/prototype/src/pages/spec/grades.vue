<template>
  <div class="page">
    <PageHeader
      title="数据等级"
      subtitle="定义表和字段的等级。不同等级决定能否直接查询、是否要审批、能否导出。建模时再落到具体表和字段上。"
    >
      <template #actions>
        <a-button v-if="canWriteSpec" @click="tplOpen = true">导入模板</a-button>
        <a-button v-if="canWriteSpec" type="primary" @click="openCreate">新增等级</a-button>
      </template>
    </PageHeader>
    <SpecReadonlyTip />

    <a-empty v-if="!grades.length" class="card" description="还没有数据等级。不知道怎么设计时，直接导入模板。">
      <a-button v-if="canWriteSpec" type="primary" @click="tplOpen = true">导入模板</a-button>
    </a-empty>

    <a-table
      v-else
      :columns="cols"
      :data-source="sorted"
      row-key="id"
      :pagination="false"
      size="small"
      class="card card-flush"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'code'">
          <a-tag :color="record.color">{{ record.code }} {{ record.name }}</a-tag>
        </template>
        <template v-else-if="column.key === 'query'">
          {{ queryLabel(record.query) }}
        </template>
        <template v-else-if="column.key === 'export'">
          {{ exportLabel(record.export) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <template v-if="canWriteSpec">
            <a @click="openEdit(record)">编辑</a>
            <a-popconfirm :title="`确定删除 ${record.code}？已引用该等级的表字段会变为未定级。`" @confirm="removeGrade(record.id)">
              <a class="danger">删除</a>
            </a-popconfirm>
          </template>
          <span v-else class="muted">只读</span>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="open" :title="editingId ? '编辑等级' : '新增等级'" ok-text="保存" @ok="save">
      <a-form layout="vertical">
        <div class="grid2">
          <a-form-item label="编码" required>
            <a-input v-model:value="form.code" placeholder="L1" :disabled="Boolean(editingId)" />
          </a-form-item>
          <a-form-item label="名称" required>
            <a-input v-model:value="form.name" placeholder="公开" />
          </a-form-item>
          <a-form-item label="排序（数字越小越开放）">
            <a-input-number v-model:value="form.level" :min="1" :max="99" style="width: 100%" />
          </a-form-item>
          <a-form-item label="颜色">
            <a-select v-model:value="form.color" :options="colorOpts" />
          </a-form-item>
          <a-form-item label="查询">
            <a-select v-model:value="form.query" :options="QUERY_OPTIONS" />
          </a-form-item>
          <a-form-item label="导出">
            <a-select v-model:value="form.export" :options="EXPORT_OPTIONS" />
          </a-form-item>
        </div>
        <a-form-item label="要求说明">
          <a-textarea v-model:value="form.note" :rows="2" placeholder="什么情况可以直接查、什么情况要审批" />
        </a-form-item>
        <a-form-item label="适用示例">
          <a-input v-model:value="form.examples" placeholder="日活汇总、手机号、结算账户" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="tplOpen" title="导入等级模板" :footer="null" width="720px">
      <p class="muted mb">选一套作为起点，导入后仍可改编码以外的规则。已有相同编码时，合并会跳过，替换会整套覆盖。</p>
      <div v-for="t in GRADE_TEMPLATES" :key="t.id" class="tpl">
        <div>
          <b>{{ t.name }}</b>
          <p>{{ t.summary }}</p>
          <div class="chips">
            <span v-for="g in t.grades" :key="g.code">
              <a-tag :color="g.color">{{ g.code }} {{ g.name }}</a-tag>
              {{ queryLabel(g.query) }}
            </span>
          </div>
        </div>
        <a-space>
          <a-button @click="useTpl(t.id, false)">合并导入</a-button>
          <a-button type="primary" @click="useTpl(t.id, true)">替换为该模板</a-button>
        </a-space>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { message } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader.vue';
import SpecReadonlyTip from '../../components/SpecReadonlyTip.vue';
import {
  EXPORT_OPTIONS,
  GRADE_TEMPLATES,
  QUERY_OPTIONS,
  exportLabel,
  queryLabel,
} from '../../config/grades';
import { addGrade, applyGradeTemplate, canWriteSpec, projectGrades, removeGrade, updateGrade } from '../../stores/app';
import type { DataGrade, ExportPolicy, QueryPolicy } from '../../types';

const grades = projectGrades;
const sorted = computed(() => [...grades.value].sort((a, b) => a.level - b.level));
const open = ref(false);
const tplOpen = ref(false);
const editingId = ref<string | null>(null);
const form = reactive({
  code: 'L1',
  name: '',
  level: 1,
  color: 'blue',
  query: 'login' as QueryPolicy,
  export: 'approval' as ExportPolicy,
  note: '',
  examples: '',
});

const colorOpts = ['green', 'blue', 'orange', 'red', 'purple', 'cyan', 'gold'].map((v) => ({ value: v, label: v }));
const cols = [
  { title: '等级', key: 'code', width: 140 },
  { title: '查询', key: 'query', width: 130 },
  { title: '导出', key: 'export', width: 120 },
  { title: '要求说明', dataIndex: 'note' },
  { title: '适用示例', dataIndex: 'examples', width: 220 },
  { title: '操作', key: 'action', width: 110 },
];

function reset() {
  form.code = `L${grades.value.length + 1}`;
  form.name = '';
  form.level = grades.value.length + 1;
  form.color = 'blue';
  form.query = 'login';
  form.export = 'approval';
  form.note = '';
  form.examples = '';
}

function openCreate() {
  editingId.value = null;
  reset();
  open.value = true;
}

function openEdit(record: DataGrade) {
  editingId.value = record.id;
  form.code = record.code;
  form.name = record.name;
  form.level = record.level;
  form.color = record.color;
  form.query = record.query;
  form.export = record.export;
  form.note = record.note;
  form.examples = record.examples;
  open.value = true;
}

function save() {
  if (!form.code || !form.name) {
    message.warning('编码和名称必填');
    return;
  }
  const payload = { ...form, code: form.code.toUpperCase() };
  const ok = editingId.value ? updateGrade(editingId.value, payload) : addGrade(payload);
  if (ok) open.value = false;
}

function useTpl(id: string, replace: boolean) {
  if (applyGradeTemplate(id, replace)) tplOpen.value = false;
}
</script>

<style scoped>
.mb {
  margin-bottom: 12px;
}
.grid2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 12px;
}
.tpl {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid #e2e8f0;
}
.tpl:last-child {
  border-bottom: 0;
}
.tpl p {
  margin: 4px 0 8px;
  color: #64748b;
  font-size: 13px;
}
.chips {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #475569;
}
</style>
