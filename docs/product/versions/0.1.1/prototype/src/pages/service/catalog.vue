<template>
  <div class="page">
    <PageHeader title="指标目录" subtitle="原子指标 × 修饰词 × 时间周期 = 派生指标；多个指标公式计算 = 复合指标。同名不同逻辑一律拒绝。">
      <template #actions>
        <a-button type="primary" @click="open = true">注册指标</a-button>
      </template>
    </PageHeader>

    <div class="filter-bar">
      <a-segmented v-model:value="type" :options="['全部', 'atomic', 'derived', 'composite']" />
    </div>

    <a-table :data-source="rows" :columns="cols" row-key="id" :pagination="false" size="small" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'type'">
          <a-tag :color="record.type === 'atomic' ? 'blue' : record.type === 'derived' ? 'cyan' : 'purple'">{{ record.type }}</a-tag>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="record.status === 'published' ? 'green' : 'default'">{{ record.status }}</a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <a @click="show(record)">详情</a>
        </template>
      </template>
    </a-table>

    <a-drawer v-model:open="detailOpen" :title="current?.name" width="520">
      <template v-if="current">
        <p>{{ current.definition }}</p>
        <p><b>计算逻辑</b></p>
        <SqlBlock :text="current.calculationLogic" />
        <p class="mt"><b>来源</b> {{ current.sourceTable }}
          <GradeTag v-if="sourceTable" :code="sourceTable.grade" policy />
        </p>
        <a-alert
          v-if="sourceAccess"
          class="mt"
          :type="sourceAccess.decision === 'forbid' ? 'error' : sourceAccess.decision === 'approval' ? 'warning' : 'success'"
          show-icon
          :message="decisionLabel(sourceAccess.decision)"
          :description="sourceAccess.reasons.join('；')"
        />
        <p><b>维度</b> {{ current.dimensions.join(', ') }}</p>
        <a-button class="mt" type="primary" ghost @click="tryQuery">试查询</a-button>
        <p><b>版本</b></p>
        <ul>
          <li v-for="c in current.changelog" :key="c.version">{{ c.version }} {{ c.date }} · {{ c.change }}</li>
        </ul>
      </template>
    </a-drawer>

    <a-modal v-model:open="open" title="注册指标" ok-text="提交" @ok="submit">
      <a-form layout="vertical">
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="支付GMV" />
        </a-form-item>
        <a-form-item label="类型">
          <a-select v-model:value="form.type" :options="['atomic', 'derived', 'composite'].map((v) => ({ value: v, label: v }))" />
        </a-form-item>
        <a-form-item label="计算逻辑" required>
          <a-textarea v-model:value="form.calculationLogic" :rows="3" />
        </a-form-item>
        <a-form-item label="来源表">
          <a-select v-model:value="form.sourceTable" :options="tables.map((t) => ({ value: t.name, label: t.name }))" />
        </a-form-item>
        <a-alert type="info" show-icon message="同名且逻辑相同会推荐复用；同名不同逻辑将被拒绝。" />
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { Modal, message } from 'ant-design-vue';
import GradeTag from '../../components/GradeTag.vue';
import PageHeader from '../../components/PageHeader.vue';
import SqlBlock from '../../components/SqlBlock.vue';
import { hydrateLayerRule } from '../../config/layerPolicies';
import { decisionLabel, evaluateAccess } from '../../engine/access';
import { projectGrades, projectLayerRules, projectMetrics, projectTables, registerMetric } from '../../stores/app';
import type { Metric, MetricType } from '../../types';

const type = ref('全部');
const open = ref(false);
const detailOpen = ref(false);
const current = ref<Metric>();
const metrics = projectMetrics;
const tables = projectTables;
const rows = computed(() => (type.value === '全部' ? metrics.value : metrics.value.filter((m) => m.type === type.value)));
const form = reactive({
  name: '',
  type: 'atomic' as MetricType,
  calculationLogic: '',
  sourceTable: tables.value.find((t) => t.layer === 'DWS')?.name ?? tables.value[0]?.name ?? '',
});

const sourceTable = computed(() => tables.value.find((t) => t.name === current.value?.sourceTable));
const sourceAccess = computed(() => {
  const t = sourceTable.value;
  if (!t) return undefined;
  const r = projectLayerRules.value.find((x) => x.layer === t.layer);
  return evaluateAccess({
    table: t,
    layerRule: r ? hydrateLayerRule(r) : undefined,
    grades: projectGrades.value,
    action: 'query',
  });
});

function tryQuery() {
  const v = sourceAccess.value;
  if (!v) {
    message.warning('找不到来源表');
    return;
  }
  if (v.decision === 'forbid') {
    message.error(v.reasons[0] || '禁止查询');
    return;
  }
  if (v.decision === 'approval') {
    Modal.confirm({
      title: '该指标来源需审批后查询',
      content: v.reasons.join('；'),
      okText: '提交审批',
      onOk() {
        message.success('审批已通过，可查询该指标');
      },
    });
    return;
  }
  message.success('当前等级与分层允许直接查询');
}

const cols = [
  { title: '名称', dataIndex: 'name', width: 160 },
  { title: '类型', key: 'type', width: 110 },
  { title: '业务过程', dataIndex: 'businessProcess', width: 120 },
  { title: '计算逻辑', dataIndex: 'calculationLogic' },
  { title: '来源表', dataIndex: 'sourceTable', width: 220 },
  { title: 'Owner', dataIndex: 'owner', width: 80 },
  { title: '版本', dataIndex: 'version', width: 80 },
  { title: '状态', key: 'status', width: 100 },
  { title: '', key: 'action', width: 70 },
];

function show(m: Metric) {
  current.value = m;
  detailOpen.value = true;
}

function submit() {
  if (!form.name || !form.calculationLogic) {
    message.warning('名称和计算逻辑必填');
    return;
  }
  const r = registerMetric({
    name: form.name,
    type: form.type,
    businessProcess: '订单支付',
    measure: 'order_pay_amt',
    aggregation: 'SUM',
    dataType: 'DECIMAL(18,2)',
    unit: '元',
    definition: form.name,
    calculationLogic: form.calculationLogic,
    sourceTable: form.sourceTable,
    dimensions: ['dt'],
    owner: '张三',
    status: 'review',
    version: 'v1.0',
    sensitivity: 'internal',
  });
  if (r) open.value = false;
}
</script>

<style scoped>
.mt { margin-top: 12px; }
</style>
