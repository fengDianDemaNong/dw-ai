<template>
  <a-modal
    :open="open"
    :title="editing ? '编辑表' : `新增 ${layerLabel} 表`"
    ok-text="保存"
    width="1100px"
    @ok="save"
    @cancel="$emit('close')"
  >
    <a-form layout="vertical">
      <div class="grid2">
        <a-form-item label="表名" required extra="项目内表名必须唯一">
          <a-input v-model:value="form.name" placeholder="dwd_trd_order_item_di" />
        </a-form-item>
        <a-form-item label="主题域">
          <a-select
            v-model:value="form.domain"
            allow-clear
            placeholder="归属主题域"
            :options="domainOpts"
          />
        </a-form-item>
        <a-form-item label="中文说明">
          <a-input v-model:value="form.comment" />
        </a-form-item>
        <a-form-item label="粒度">
          <a-input v-model:value="form.grain" placeholder="订单项 / 用户" />
        </a-form-item>
        <a-form-item label="周期">
          <a-select v-model:value="form.period" :options="periods" />
        </a-form-item>
        <a-form-item label="状态">
          <a-select v-model:value="form.status" :options="statuses" />
        </a-form-item>
        <a-form-item label="分区">
          <a-input v-model:value="form.partition" placeholder="dt" />
        </a-form-item>
        <a-form-item label="数据等级">
          <a-select
            v-model:value="form.grade"
            allow-clear
            placeholder="从表/字段等级规范中选择"
            :options="gradeOpts"
          />
        </a-form-item>
      </div>
    </a-form>
    <p v-if="!gradeOpts.length" class="muted hint">
      还没有数据等级。
      <a @click="goGrades">去规范中心定义或导入模板</a>
    </p>
    <div class="col-h">
      <b>字段</b>
      <a-button size="small" @click="addCol">加字段</a-button>
    </div>
    <a-table :data-source="form.columns" :columns="colCols" :pagination="false" size="small" row-key="_k">
      <template #bodyCell="{ column, record, index }">
        <template v-if="column.key === 'name'">
          <a-input v-model:value="record.name" size="small" />
        </template>
        <template v-else-if="column.key === 'type'">
          <a-auto-complete v-model:value="record.type" size="small" :options="types" style="width: 140px" />
        </template>
        <template v-else-if="column.key === 'comment'">
          <a-input v-model:value="record.comment" size="small" />
        </template>
        <template v-else-if="column.key === 'grade'">
          <a-select
            v-model:value="record.grade"
            allow-clear
            size="small"
            placeholder="继承表"
            :options="gradeOpts"
            style="width: 118px"
          />
        </template>
        <template v-else-if="column.key === 'notNull'">
          <a-checkbox
            :checked="record.nullable === false"
            @update:checked="(v: boolean) => (record.nullable = !v)"
          />
        </template>
        <template v-else-if="column.key === 'default'">
          <a-input
            v-model:value="record.defaultValue"
            size="small"
            placeholder="如 0"
            :disabled="record.nullable !== false"
          />
        </template>
        <template v-else-if="column.key === 'enum'">
          <a-input
            size="small"
            :value="(record.enumValues ?? []).join(',')"
            placeholder="可选"
            @update:value="
              (v: string) =>
                (record.enumValues = v
                  .split(/[,，]/)
                  .map((s) => s.trim())
                  .filter(Boolean))
            "
          />
        </template>
        <template v-else-if="column.key === 'sensitive'">
          <a-checkbox v-model:checked="record.sensitive" />
        </template>
        <template v-else-if="column.key === 'op'">
          <a class="danger" @click="form.columns.splice(index, 1)">删</a>
        </template>
      </template>
    </a-table>
    <p class="muted hint">勾选「不为空」后可填默认值，会写入 DDL 的 DEFAULT。枚举值可选，生成草案时可用于建议质量规则。</p>
    <div v-if="previewSpec" class="ddl-wrap">
      <DdlPreview :spec="previewSpec" title="生成 DDL" />
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import { specFromTable } from '@dw-ai/engine';
import DdlPreview from './DdlPreview.vue';
import { addTable, projectDomains, projectGrades, updateTable } from '../stores/app';
import type { Column, WarehouseTable } from '../types';

const props = defineProps<{
  open: boolean;
  table?: WarehouseTable | null;
  defaultDomain?: string;
  layer?: string;
}>();
const emit = defineEmits<{ close: []; saved: [WarehouseTable] }>();
const router = useRouter();

type ColRow = Column & { _k: string };

const form = reactive({
  name: '',
  comment: '',
  domain: undefined as string | undefined,
  grain: '',
  period: 'di',
  status: 'published' as WarehouseTable['status'],
  partition: 'dt',
  grade: undefined as string | undefined,
  columns: [] as ColRow[],
});

const domainOpts = computed(() =>
  projectDomains.value.map((d) => ({ value: d.code, label: `${d.code} ${d.name}` }))
);
const gradeOpts = computed(() =>
  projectGrades.value.map((g) => ({ value: g.code, label: `${g.code} ${g.name}` }))
);
const layerLabel = computed(() => props.table?.layer || props.layer || 'DWD');
const editing = computed(() => Boolean(props.table));
const periods = ['di', 'df', 'hi', 'hf', 'wi', 'mi'].map((v) => ({ value: v, label: v }));
const statuses = [
  { value: 'draft', label: '草稿' },
  { value: 'published', label: '已发布' },
  { value: 'deprecated', label: '已下线' },
];
const types = ['STRING', 'BIGINT', 'INT', 'DECIMAL(18,2)', 'DOUBLE', 'BOOLEAN', 'DATETIME'].map((v) => ({ value: v }));
const colCols = [
  { title: '字段名', key: 'name', width: 140 },
  { title: '类型', key: 'type', width: 130 },
  { title: '注释', key: 'comment' },
  { title: '等级', key: 'grade', width: 124 },
  { title: '不为空', key: 'notNull', width: 64 },
  { title: '默认值', key: 'default', width: 100 },
  { title: '枚举', key: 'enum', width: 110 },
  { title: '敏感', key: 'sensitive', width: 52 },
  { title: '', key: 'op', width: 40 },
];
const previewSpec = computed(() => {
  const columns = form.columns.filter((c) => c.name.trim());
  if (!form.name.trim() || !columns.length) return null;
  return specFromTable(
    {
      name: form.name.trim(),
      comment: form.comment,
      domain: form.domain,
      grain: form.grain,
      grade: form.grade,
      partition: form.partition,
      columns,
    },
    { grades: projectGrades.value }
  );
});

function fill() {
  const t = props.table;
  form.name = t?.name ?? '';
  form.comment = t?.comment ?? '';
  form.domain = t?.domain ?? props.defaultDomain;
  form.grain = t?.grain ?? '';
  form.period = t?.period ?? 'di';
  form.status = t?.status ?? 'published';
  form.partition = t?.partition ?? 'dt';
  form.grade = t?.grade;
  form.columns = (t?.columns ?? []).map((c, i) => ({ ...c, _k: `${c.name}-${i}` }));
  if (!form.columns.length) addCol();
}

function addCol() {
  form.columns.push({
    _k: `c-${Date.now()}-${form.columns.length}`,
    name: '',
    type: 'STRING',
    comment: '',
    nullable: true,
    sensitive: false,
  });
}

function goGrades() {
  emit('close');
  router.push('/w/spec/grades');
}

function save() {
  if (!form.name) {
    message.warning('表名必填');
    return;
  }
  const cols = form.columns
    .filter((c) => c.name.trim())
    .map(({ _k, ...c }) => ({
      ...c,
      defaultValue:
        c.nullable === false && c.defaultValue?.trim() ? c.defaultValue.trim() : undefined,
      enumValues: c.enumValues?.length ? c.enumValues : undefined,
    }));
  if (!cols.length) {
    message.warning('至少保留一个字段');
    return;
  }
  const payload: Omit<WarehouseTable, 'id' | 'projectId'> = {
    layer: layerLabel.value,
    name: form.name.trim(),
    comment: form.comment,
    domain: form.domain,
    grain: form.grain,
    period: form.period,
    status: form.status,
    partition: form.partition,
    grade: form.grade,
    columns: cols,
    createdFrom: props.table?.createdFrom,
    sourceSystem: props.table?.sourceSystem,
  };
  const row = props.table ? updateTable(props.table.id, payload) : addTable(payload);
  if (row) {
    emit('saved', row);
    emit('close');
  }
}

watch(
  () => [props.open, props.table, props.defaultDomain] as const,
  () => {
    if (props.open) fill();
  },
  { immediate: true }
);
</script>

<style scoped>
.grid2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 12px;
}
.hint {
  margin: 0 0 8px;
}
.col-h {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 4px 0 8px;
}
.ddl-wrap {
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px dashed var(--line);
}
</style>
