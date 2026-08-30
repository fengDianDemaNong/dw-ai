<template>
  <div class="page">
    <PageHeader title="API 网关" subtitle="指标发布后自动生成查询 API。表级查询策略来自分层 serve 与数据等级，不再是静态说明。" />

    <div class="grid">
      <div class="card">
        <h3>自动生成契约</h3>
        <SqlBlock :text="spec" />
      </div>
      <div class="card">
        <h3>分层对外策略</h3>
        <a-table :data-source="layerPerm" :columns="lcols" size="small" :pagination="false" row-key="layer" />
      </div>
    </div>

    <div class="card mt">
      <h3>表级查询拦截</h3>
      <p class="muted">取数前取分层 serve 与表/字段等级的最严结果。禁止则拒绝，审批则需确认。</p>
      <a-table :data-source="tablePerm" :columns="tcols" size="small" :pagination="false" row-key="name">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'decision'">
            <a-tag :color="decisionColor(record.decision)">{{ decisionLabel(record.decision) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'grade'">
            <GradeTag :code="record.grade" />
          </template>
          <template v-else-if="column.key === 'action'">
            <a @click="tryQuery(record.name)">模拟查询</a>
          </template>
        </template>
      </a-table>
    </div>

    <div class="card mt">
      <h3>调用审计</h3>
      <a-table :data-source="calls" :columns="acols" size="small" :pagination="false" row-key="id">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'hit'">
            <a-tag :color="record.cacheHit ? 'green' : 'default'">{{ record.cacheHit ? 'HIT' : 'MISS' }}</a-tag>
          </template>
        </template>
      </a-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { Modal, message } from 'ant-design-vue';
import GradeTag from '../../components/GradeTag.vue';
import PageHeader from '../../components/PageHeader.vue';
import SqlBlock from '../../components/SqlBlock.vue';
import { hydrateLayerRule, maskingLabel } from '../../config/layerPolicies';
import { decisionColor, decisionLabel, evaluateAccess } from '../../engine/access';
import { projectApiCalls, projectGrades, projectLayerRules, projectTables } from '../../stores/app';

const calls = projectApiCalls;
const spec = `POST /api/v1/metrics/{metric_id}/query
Auth: Bearer Token + AppKey

{
  "dimensions": ["dt", "user_type"],
  "filters": [
    {"field": "dt", "op": ">=", "value": "2026-08-01"}
  ],
  "time_grain": "day",
  "limit": 1000
}`;

const layerPerm = computed(() =>
  projectLayerRules.value.map((r) => {
    const h = hydrateLayerRule(r);
    return {
      layer: r.layer,
      serve: r.serve === 'forbid' ? '禁止对外' : r.serve === 'approval' ? '审批后可服务' : '允许服务',
      masking: maskingLabel(h.masking),
      note: r.note,
    };
  })
);
const lcols = [
  { title: '分层', dataIndex: 'layer', width: 90 },
  { title: '对外服务', dataIndex: 'serve', width: 140 },
  { title: '脱敏', dataIndex: 'masking', width: 90 },
  { title: '说明', dataIndex: 'note' },
];

const tablePerm = computed(() =>
  projectTables.value
    .filter((t) => t.layer !== 'ODS')
    .map((t) => {
      const r = projectLayerRules.value.find((x) => x.layer === t.layer);
      const v = evaluateAccess({
        table: t,
        layerRule: r ? hydrateLayerRule(r) : undefined,
        grades: projectGrades.value,
        action: 'query',
      });
      return {
        name: t.name,
        layer: t.layer,
        grade: t.grade,
        decision: v.decision,
        reasons: v.reasons.join('；'),
      };
    })
);
const tcols = [
  { title: '表', dataIndex: 'name' },
  { title: '分层', dataIndex: 'layer', width: 80 },
  { title: '等级', key: 'grade', width: 120 },
  { title: '查询策略', key: 'decision', width: 140 },
  { title: '原因', dataIndex: 'reasons' },
  { title: '', key: 'action', width: 90 },
];

function tryQuery(name: string) {
  const row = tablePerm.value.find((x) => x.name === name);
  if (!row) return;
  if (row.decision === 'forbid') {
    message.error(row.reasons || '禁止查询');
    return;
  }
  if (row.decision === 'approval') {
    Modal.confirm({
      title: `查询 ${name} 需审批`,
      content: row.reasons,
      okText: '提交审批',
      onOk() {
        message.success('审批已通过（演示）');
      },
    });
    return;
  }
  message.success(`${name} 允许直接查询`);
}

const acols = [
  { title: '调用', dataIndex: 'id', width: 90 },
  { title: 'App', dataIndex: 'appKey', width: 140 },
  { title: '指标', dataIndex: 'metricId', width: 140 },
  { title: '用户', dataIndex: 'userId', width: 80 },
  { title: '行数', dataIndex: 'returnRows', width: 80 },
  { title: '耗时', dataIndex: 'costMs', width: 80 },
  { title: '缓存', key: 'hit', width: 80 },
  { title: '时间', dataIndex: 'timestamp' },
];
</script>

<style scoped>
h3 { margin: 0 0 10px; font-size: 14px; }
.grid { display: grid; grid-template-columns: 1.1fr 1fr; gap: 12px; }
.mt { margin-top: 12px; }
.muted { color: #64748b; font-size: 12px; margin: 0 0 10px; }
</style>
