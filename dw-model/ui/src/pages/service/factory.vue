<template>
  <div class="page">
    <PageHeader title="指标工厂" subtitle="拖拉拽配置维度/度量/筛选，系统生成 SQL 并按物化表覆盖情况智能路由。取数受分层对外策略与数据等级约束。">
      <template #actions>
        <a-button @click="preview">预览</a-button>
        <a-button type="primary" @click="publish">发布为指标</a-button>
      </template>
    </PageHeader>

    <a-alert
      v-if="accessTable"
      class="mb"
      :type="access.decision === 'forbid' ? 'error' : access.decision === 'approval' ? 'warning' : 'success'"
      show-icon
      :message="`${accessTable.name} · ${decisionLabel(access.decision)}`"
      :description="access.reasons.join('；')"
    />

    <div class="factory">
      <section>
        <h3>① 选择数据源</h3>
        <a-radio-group v-model:value="cfg.layer" @change="onLayer">
          <a-radio value="DWD">DWD 明细</a-radio>
          <a-radio value="DWS">DWS 汇总</a-radio>
        </a-radio-group>
        <a-select v-model:value="cfg.table" style="width: 100%; margin-top: 8px" :options="tableOpts" @change="resetDims" />
        <div v-if="accessTable" class="src-meta">
          表等级 <GradeTag :code="accessTable.grade" policy />
          · 分层对外 {{ serveText }}
        </div>
      </section>
      <section>
        <h3>② 配置维度</h3>
        <a-checkbox-group v-model:value="cfg.dimensions">
          <div v-for="c in dimCols" :key="c.name">
            <a-checkbox :value="c.name">{{ c.comment || c.name }} <code>{{ c.name }}</code></a-checkbox>
          </div>
        </a-checkbox-group>
      </section>
      <section>
        <h3>③ 配置度量</h3>
        <div v-for="(m, i) in cfg.measures" :key="i" class="mrow">
          <a-select v-model:value="m.field" style="width: 140px" :options="measureOpts" />
          <a-select v-model:value="m.agg" style="width: 130px" :options="aggOpts(m.field)" />
          <a-input v-model:value="m.alias" style="width: 100px" />
        </div>
        <div v-if="hints.length" class="muted">常一起用：{{ hints.join('、') }}</div>
      </section>
      <section>
        <h3>④ 筛选条件</h3>
        <div v-for="(f, i) in cfg.filters" :key="i" class="mrow">
          <a-select v-model:value="f.field" style="width: 130px" :options="allCols" />
          <a-select v-model:value="f.op" style="width: 80px" :options="ops" />
          <a-input v-model:value="f.value" style="width: 120px" />
        </div>
        <a-button size="small" @click="addFilter">+ 条件</a-button>
      </section>
      <section>
        <h3>⑤ 时间周期</h3>
        <a-radio-group v-model:value="cfg.timeGrain">
          <a-radio value="day">日</a-radio>
          <a-radio value="week">周</a-radio>
          <a-radio value="month">月</a-radio>
        </a-radio-group>
        <a-select v-model:value="cfg.timeRange" style="width: 100%; margin-top: 8px" :options="['近7天', '近30天', '本月'].map((v) => ({ value: v, label: v }))" />
      </section>
      <section>
        <h3>⑥ 高级</h3>
        <a-checkbox v-model:checked="cfg.nullFill">空值填充 0</a-checkbox>
        <div><a-checkbox v-model:checked="cfg.yoy">同比环比</a-checkbox></div>
      </section>
    </div>

    <div class="card mt">
      <h3>实时预览（Top 100）</h3>
      <p v-if="access.decision === 'forbid'" class="muted">当前分层或等级禁止查询，不返回结果。</p>
      <p v-else-if="access.decision === 'approval' && !approved" class="muted">需审批后才可查看预览数据，请点「预览」确认。</p>
      <a-table v-else :data-source="rows" :columns="previewCols" size="small" :pagination="false" :row-key="rowKey" />
    </div>

    <div class="card mt">
      <div class="row">
        <h3>生成 SQL</h3>
        <a-tag v-if="routed.hit" color="cyan">物化表命中: {{ routed.hit }}</a-tag>
        <a-select v-model:value="dialect" style="width: 140px; margin-left: auto" :options="['StarRocks', 'Hive', 'Spark'].map((v) => ({ value: v, label: v }))" />
      </div>
      <SqlBlock :text="routed.sql" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { Modal, message } from 'ant-design-vue';
import GradeTag from '../../components/GradeTag.vue';
import PageHeader from '../../components/PageHeader.vue';
import SqlBlock from '../../components/SqlBlock.vue';
import { hydrateLayerRule } from '../../config/layerPolicies';
import { decisionLabel, evaluateAccess } from '../../engine/access';
import { previewRows, recommendMeasures, routeQuery } from '../../engine/metrics';
import {
  addQueryFromFactory,
  projectGrades,
  projectLayerRules,
  projectMetrics,
  projectRecs,
  projectTables,
  registerMetric,
} from '../../stores/app';
import type { FactoryConfig } from '../../types';

const dialect = ref('StarRocks');
const tables = projectTables;
const firstDws = tables.value.find((t) => t.layer === 'DWS');
const firstLayer = tables.value.find((t) => t.layer === (firstDws ? 'DWS' : 'DWD'));
const initMeasure = firstLayer?.columns.find((c) => /gmv|amt/.test(c.name));
const cfg = reactive<FactoryConfig>({
  layer: firstDws ? 'DWS' : 'DWD',
  table: firstLayer?.name ?? '',
  dimensions: (firstLayer?.columns ?? [])
    .filter((c) => ['dt', 'user_type'].includes(c.name))
    .map((c) => c.name),
  measures: initMeasure
    ? [{ field: initMeasure.name, agg: 'SUM', alias: initMeasure.name === 'gmv' ? 'gmv' : 'amt' }]
    : [],
  filters: firstLayer?.columns.some((c) => c.name === 'dt') ? [{ field: 'dt', op: '>=', value: '2026-08-20' }] : [],
  timeGrain: 'day',
  timeRange: '近7天',
  nullFill: true,
  yoy: true,
});

const approved = ref(false);
const currentTable = computed(() => tables.value.find((t) => t.name === cfg.table));
const tableOpts = computed(() =>
  tables.value.filter((t) => t.layer === cfg.layer).map((t) => ({ value: t.name, label: t.name }))
);
const dimCols = computed(() =>
  (currentTable.value?.columns ?? []).filter((c) => !/amt|cnt|gmv|qty/.test(c.name))
);
const measureCols = computed(() =>
  (currentTable.value?.columns ?? []).filter((c) => /amt|cnt|gmv|qty|decimal|bigint|int/i.test(c.name + c.type))
);
const measureOpts = computed(() => measureCols.value.map((c) => ({ value: c.name, label: c.name })));
const allCols = computed(() => (currentTable.value?.columns ?? []).map((c) => ({ value: c.name, label: c.name })));
const ops = ['=', '>=', '<=', 'IN', 'LIKE'].map((v) => ({ value: v, label: v }));

function aggOpts(field: string) {
  const col = currentTable.value?.columns.find((c) => c.name === field);
  const numeric = col ? /decimal|bigint|int|double/i.test(col.type) : true;
  const all = [
    { value: 'SUM', label: 'SUM', disabled: !numeric },
    { value: 'AVG', label: 'AVG', disabled: !numeric },
    { value: 'COUNT', label: 'COUNT' },
    { value: 'COUNT_DISTINCT', label: 'COUNT DISTINCT' },
    { value: 'MAX', label: 'MAX' },
    { value: 'MIN', label: 'MIN' },
  ];
  return all;
}

const hints = computed(() => recommendMeasures(cfg.dimensions, currentTable.value, projectMetrics.value));
const routed = computed(() => routeQuery(cfg, projectRecs.value, tables.value));
const accessTable = computed(
  () => tables.value.find((t) => t.name === routed.value.hit) ?? currentTable.value
);
const layerRule = computed(() => {
  const layer = accessTable.value?.layer ?? cfg.layer;
  const r = projectLayerRules.value.find((x) => x.layer === layer);
  return r ? hydrateLayerRule(r) : undefined;
});
const queryFields = computed(() =>
  [...cfg.dimensions, ...cfg.measures.map((m) => m.field), ...cfg.filters.map((f) => f.field)].filter(Boolean)
);
const access = computed(() =>
  evaluateAccess({
    table: accessTable.value,
    layerRule: layerRule.value,
    grades: projectGrades.value,
    fields: queryFields.value,
    action: 'query',
  })
);
const serveText = computed(() => {
  const s = layerRule.value?.serve;
  if (s === 'forbid') return '禁止';
  if (s === 'approval') return '需审批';
  return '允许';
});
const rows = computed(() => previewRows(cfg));
const previewCols = computed(() => {
  const dims = cfg.dimensions.map((d) => ({ title: d, dataIndex: d, key: d }));
  const ms = cfg.measures.map((m) => ({ title: m.alias, dataIndex: m.alias, key: m.alias }));
  return [...dims, ...ms];
});

watch(
  () => [cfg.table, cfg.layer, cfg.dimensions.join(), cfg.measures.map((m) => m.field).join()].join('|'),
  () => {
    approved.value = false;
  }
);

function rowKey(r: Record<string, string | number>) {
  return Object.values(r).join('|');
}

function onLayer() {
  const first = tables.value.find((t) => t.layer === cfg.layer);
  cfg.table = first?.name ?? '';
  resetDims();
}

function resetDims() {
  const t = currentTable.value;
  cfg.dimensions = t?.columns.some((c) => c.name === 'dt') ? ['dt'] : [];
  const m = t?.columns.find((c) => /gmv|amt/.test(c.name));
  cfg.measures = m ? [{ field: m.name, agg: 'SUM', alias: m.name === 'gmv' ? 'gmv' : 'amt' }] : [];
  cfg.filters = t?.columns.some((c) => c.name === 'dt') ? [{ field: 'dt', op: '>=', value: '2026-08-20' }] : [];
}

function addFilter() {
  const field = currentTable.value?.columns[0]?.name ?? 'dt';
  cfg.filters.push({ field, op: '=', value: '' });
}

function runPreview() {
  if (!cfg.dimensions.includes('dt') && !cfg.filters.some((f) => f.field === 'dt')) {
    message.error('必须选择时间维度或时间筛选');
    return false;
  }
  addQueryFromFactory(cfg);
  message.success(routed.value.hit ? `已路由到 ${routed.value.hit}` : '走原始表执行（Top 100 预览）');
  return true;
}

function preview() {
  if (!accessTable.value) {
    message.warning('请先选择数据源');
    return;
  }
  if (access.value.decision === 'forbid') {
    message.error(access.value.reasons[0] || '当前分层或等级禁止查询');
    return;
  }
  if (access.value.decision === 'approval' && !approved.value) {
    Modal.confirm({
      title: '该查询需审批',
      content: access.value.reasons.join('；'),
      okText: '提交审批并查看',
      onOk() {
        approved.value = true;
        runPreview();
      },
    });
    return;
  }
  runPreview();
}

function publish() {
  if (access.value.decision === 'forbid') {
    message.error('禁止查询的数据源不能发布为指标');
    return;
  }
  if (access.value.decision === 'approval' && !approved.value) {
    Modal.confirm({
      title: '发布前需审批查询',
      content: access.value.reasons.join('；'),
      okText: '审批通过并发布',
      onOk() {
        approved.value = true;
        if (runPreview()) doPublish();
      },
    });
    return;
  }
  if (!runPreview()) return;
  doPublish();
}

function doPublish() {
  registerMetric({
    name: cfg.measures[0]?.alias ?? '未命名指标',
    type: 'atomic',
    businessProcess: '指标工厂',
    measure: cfg.measures[0]?.field ?? '',
    aggregation: cfg.measures[0]?.agg ?? 'SUM',
    dataType: 'DECIMAL(18,2)',
    unit: '元',
    definition: `由指标工厂生成：${cfg.dimensions.join('×')} / ${cfg.measures.map((m) => m.alias).join(',')}`,
    calculationLogic: routed.value.sql.split('\n').join(' '),
    sourceTable: routed.value.hit ?? cfg.table,
    dimensions: cfg.dimensions,
    owner: '张三',
    status: 'draft',
    version: 'v1.0',
    sensitivity: 'internal',
  });
}
</script>

<style scoped>
.factory {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
section {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 14px;
}
h3 { margin: 0 0 10px; font-size: 13px; }
.mrow { display: flex; gap: 6px; margin-bottom: 6px; }
.mt { margin-top: 12px; }
.mb { margin-bottom: 12px; }
.row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
code { font-size: 11px; color: #64748b; }
.src-meta { margin-top: 8px; font-size: 12px; color: #64748b; }
</style>
