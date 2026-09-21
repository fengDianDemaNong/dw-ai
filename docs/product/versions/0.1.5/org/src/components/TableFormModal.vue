<template>
  <a-modal
    :open="open"
    :title="editing ? '编辑表' : `新增 ${layerLabel} 表`"
    ok-text="保存"
    width="1200px"
    @ok="save"
    @cancel="$emit('close')"
  >
    <a-form layout="vertical">
      <div class="grid2">
        <a-form-item label="表名" required>
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
      <b>来源表</b>
      <a-button size="small" @click="addSource">加来源</a-button>
    </div>
    <a-table :data-source="form.sources" :columns="srcCols" :pagination="false" size="small" row-key="_k">
      <template #bodyCell="{ column, record, index }">
        <template v-if="column.key === 'table'">
          <a-select
            v-model:value="record.tableId"
            size="small"
            style="width: 280px"
            :options="tableOpts"
            placeholder="本项目表"
          />
        </template>
        <template v-else-if="column.key === 'alias'">
          <a-input v-model:value="record.alias" size="small" style="width: 80px" />
        </template>
        <template v-else-if="column.key === 'op'">
          <a class="danger" @click="form.sources.splice(index, 1)">删</a>
        </template>
      </template>
    </a-table>
    <div v-if="form.sources.length > 1" class="col-h">
      <b>关联</b>
      <a-button size="small" @click="addJoin">加关联</a-button>
    </div>
    <a-table
      v-if="form.sources.length > 1"
      :data-source="form.joins"
      :columns="joinCols"
      :pagination="false"
      size="small"
      row-key="_k"
    >
      <template #bodyCell="{ column, record, index }">
        <template v-if="column.key === 'left'">
          <a-input v-model:value="record.leftAlias" size="small" style="width: 56px" placeholder="别名" />
          .
          <a-input v-model:value="record.leftColumn" size="small" style="width: 120px" placeholder="字段" />
        </template>
        <template v-else-if="column.key === 'right'">
          <a-input v-model:value="record.rightAlias" size="small" style="width: 56px" />
          .
          <a-input v-model:value="record.rightColumn" size="small" style="width: 120px" />
        </template>
        <template v-else-if="column.key === 'type'">
          <a-select v-model:value="record.type" size="small" style="width: 90px" :options="joinTypes" />
        </template>
        <template v-else-if="column.key === 'op'">
          <a class="danger" @click="form.joins.splice(index, 1)">删</a>
        </template>
      </template>
    </a-table>
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
        <template v-else-if="column.key === 'sensitive'">
          <a-checkbox v-model:checked="record.sensitive" />
        </template>
        <template v-else-if="column.key === 'kind'">
          <a-select
            :value="record.logic?.kind"
            allow-clear
            size="small"
            style="width: 96px"
            :options="kindOpts"
            @change="(v: string) => setLogic(record, { kind: (v || undefined) as FieldLogicKind | undefined })"
          />
        </template>
        <template v-else-if="column.key === 'desc'">
          <a-input
            :value="record.logic?.desc"
            size="small"
            placeholder="口径"
            @update:value="(v: string) => setLogic(record, { desc: v })"
          />
        </template>
        <template v-else-if="column.key === 'expr'">
          <a-input
            :value="record.logic?.expr || srcText(record)"
            size="small"
            placeholder="o.col / SUM / 派生"
            @update:value="(v: string) => setLogic(record, { expr: v })"
          />
        </template>
        <template v-else-if="column.key === 'op'">
          <a class="danger" @click="form.columns.splice(index, 1)">删</a>
        </template>
      </template>
    </a-table>
    <p class="muted hint">勾选「不为空」后可填默认值，会写入 DDL 的 DEFAULT。保存后为草稿，在表明细点「发布」才会生成新版本。</p>
    <div v-if="previewSpec" class="ddl-wrap">
      <DdlPreview :spec="previewSpec" :dml-sql="previewDml" />
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import { resolvedSources, specFromTable, renderEtlSql, LOGIC_KIND_LABEL, LOGIC_KIND_ORDER } from '@dw-ai/engine';
import DdlPreview from './DdlPreview.vue';
import { confirmImpact } from './confirmImpact';
import { addTable, impactForUpdate, projectDomains, projectGrades, projectTables, updateTable } from '../stores/app';
import type { Column, FieldLogic, FieldLogicKind, TableJoin, TableSourceRef, WarehouseTable } from '../types';

const props = defineProps<{
  open: boolean;
  table?: WarehouseTable | null;
  defaultDomain?: string;
  layer?: string;
}>();
const emit = defineEmits<{ close: []; saved: [WarehouseTable] }>();
const router = useRouter();

type ColRow = Column & { _k: string };
type SrcRow = TableSourceRef & { _k: string };
type JoinRow = TableJoin & { _k: string };

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
  sources: [] as SrcRow[],
  joins: [] as JoinRow[],
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
const types = ['STRING', 'BIGINT', 'INT', 'DECIMAL(18,2)', 'DOUBLE', 'BOOLEAN', 'DATETIME'].map((v) => ({ value: v }));
const colCols = [
  { title: '字段名', key: 'name', width: 140 },
  { title: '类型', key: 'type', width: 130 },
  { title: '注释', key: 'comment' },
  { title: '等级', key: 'grade', width: 124 },
  { title: '不为空', key: 'notNull', width: 64 },
  { title: '默认值', key: 'default', width: 110 },
  { title: '敏感', key: 'sensitive', width: 52 },
  { title: '加工', key: 'kind', width: 100 },
  { title: '口径', key: 'desc', width: 140 },
  { title: '表达式', key: 'expr', width: 160 },
  { title: '', key: 'op', width: 40 },
];
const srcCols = [
  { title: '来源表', key: 'table' },
  { title: '别名', key: 'alias', width: 90 },
  { title: '', key: 'op', width: 40 },
];
const joinCols = [
  { title: '左', key: 'left' },
  { title: '右', key: 'right' },
  { title: '类型', key: 'type', width: 100 },
  { title: '', key: 'op', width: 40 },
];
const kindOpts = LOGIC_KIND_ORDER.map((k) => ({ value: k, label: LOGIC_KIND_LABEL[k] }));
const joinTypes = [
  { value: 'inner', label: '内连接' },
  { value: 'left', label: '左连接' },
];
const tableOpts = computed(() =>
  projectTables.value
    .filter((t) => t.id !== props.table?.id)
    .map((t) => ({ value: t.id, label: `${t.name}（${t.layer}）` }))
);
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
const previewSources = computed(() => form.sources.filter((s) => s.tableId && s.alias).map(({ _k, ...s }) => s));
const previewDml = computed(() => {
  if (!previewSources.value.length) return undefined;
  const columns = form.columns.filter((c) => c.name.trim());
  if (!form.name.trim() || !columns.length) return '';
  const joins = form.joins
    .filter((j) => j.leftAlias && j.rightAlias && j.leftColumn && j.rightColumn)
    .map(({ _k, ...j }) => j);
  return renderEtlSql(
    {
      name: form.name.trim(),
      sources: previewSources.value,
      createdFrom: previewSources.value[0]?.tableId,
      joins,
      columns,
      partition: form.partition,
    },
    projectTables.value
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
  form.columns = (t?.columns ?? []).map((c, i) => ({ ...c, logic: c.logic ? { ...c.logic } : undefined, _k: `${c.name}-${i}` }));
  form.sources = resolvedSources(t ?? { sources: [], createdFrom: undefined }).map((s, i) => ({
    ...s,
    _k: `${s.alias}-${i}`,
  }));
  form.joins = (t?.joins ?? []).map((j, i) => ({ ...j, type: j.type ?? 'inner', _k: `j-${i}` }));
  if (!form.columns.length) addCol();
}

function addSource() {
  form.sources.push({ _k: `s-${Date.now()}`, tableId: '', alias: form.sources.length ? `t${form.sources.length}` : 's' });
}
function addJoin() {
  form.joins.push({
    _k: `j-${Date.now()}`,
    leftAlias: form.sources[0]?.alias ?? 'o',
    leftColumn: 'dt',
    rightAlias: form.sources[1]?.alias ?? 'r',
    rightColumn: 'dt',
    type: 'left',
  });
}
function setLogic(record: ColRow, patch: Partial<FieldLogic>) {
  record.logic = { ...(record.logic ?? { kind: 'passthrough' }), ...patch };
  if (!record.logic.kind && !record.logic.desc && !record.logic.expr) record.logic = undefined;
}
function srcText(record: ColRow) {
  return (record.logic?.sources ?? []).map((s) => `${s.alias}.${s.column}`).join(', ');
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
  router.push('/app/spec/grades');
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
    }));
  if (!cols.length) {
    message.warning('至少保留一个字段');
    return;
  }
  const sources = form.sources.filter((s) => s.tableId && s.alias).map(({ _k, ...s }) => s);
  const joins = form.joins
    .filter((j) => j.leftAlias && j.rightAlias && j.leftColumn && j.rightColumn)
    .map(({ _k, ...j }) => j);
  const payload: Omit<WarehouseTable, 'id' | 'projectId'> = {
    layer: layerLabel.value,
    name: form.name.trim(),
    comment: form.comment,
    domain: form.domain,
    grain: form.grain,
    period: form.period,
    status: 'draft',
    partition: form.partition,
    grade: form.grade,
    columns: cols,
    createdFrom: sources[0]?.tableId ?? props.table?.createdFrom,
    sources,
    joins,
    sourceSystem: props.table?.sourceSystem,
  };
  const commit = () => {
    const row = props.table ? updateTable(props.table.id, payload) : addTable(payload);
    if (row) {
      emit('saved', row);
      emit('close');
    }
  };
  if (props.table) {
    const report = impactForUpdate(props.table.id, payload);
    confirmImpact(
      report
        ? {
            ...report,
            title: report.needConfirm ? report.title : '保存为草稿',
            lines: [
              '保存后状态为草稿，发布才会生成新版本。',
              ...(report.lines ?? []),
            ],
          }
        : undefined,
      commit,
      { always: true, okText: '保存草稿' }
    );
    return;
  }
  commit();
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
