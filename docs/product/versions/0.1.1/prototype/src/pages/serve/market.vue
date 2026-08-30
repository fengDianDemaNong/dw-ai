<template>
  <div class="page">
    <PageHeader title="数据市场" subtitle="看表、看已发布口径。公共指标是公司统一口径；业务指标供各部门评估是否复用。与旧版「指标市场」独立，便于对照。">
      <template #actions>
        <a-button @click="router.push('/w/serve/factory')">去新版指标工厂</a-button>
      </template>
    </PageHeader>

    <a-tabs v-model:activeKey="tab">
      <a-tab-pane key="tables" tab="基础信息" />
      <a-tab-pane key="public" tab="公共指标" />
      <a-tab-pane key="business" tab="业务指标" />
    </a-tabs>

    <div v-if="tab === 'tables'" class="card card-flush">
      <p class="hint">展示 DWD / DWS / ADS 的定义与预览。ODS 不对外。预览走分层 serve 与数据等级。</p>
      <a-table :data-source="tables" :columns="tableCols" size="small" :pagination="false" row-key="id">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'grade'">
            <GradeTag :code="record.grade" policy />
          </template>
          <template v-else-if="column.key === 'action'">
            <a @click="openTable(record)">详情 / 预览</a>
          </template>
        </template>
      </a-table>
    </div>

    <div v-else class="card card-flush">
      <p class="hint">{{ tab === 'public' ? '全公司统一口径，同名禁止再注册一套算法。' : '业务人员已发布的指标，可查看后复制到个人空间再改。' }}</p>
      <a-table :data-source="metricRows" :columns="metricCols" size="small" :pagination="false" row-key="id">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'folder'">{{ folderName(record.folderId) }}</template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="record.status === 'published' ? 'green' : 'orange'">{{ record.status === 'published' ? '已发布' : '待审' }}</a-tag>
          </template>
        </template>
      </a-table>
    </div>

    <a-drawer v-model:open="drawer" :title="currentTable?.name" width="640">
      <template v-if="currentTable">
        <p>{{ currentTable.comment || '暂无定义' }}</p>
        <p>
          <b>分层</b> {{ currentTable.layer }} · <b>主题域</b> {{ currentTable.domain || '—' }} ·
          <b>粒度</b> {{ currentTable.grain || '—' }}
        </p>
        <p><GradeTag :code="currentTable.grade" policy /></p>
        <a-alert
          class="mb"
          :type="access.decision === 'forbid' ? 'error' : access.decision === 'approval' ? 'warning' : 'success'"
          show-icon
          :message="decisionLabel(access.decision)"
          :description="access.reasons.join('；')"
        />
        <h4>字段</h4>
        <a-table :data-source="currentTable.columns" :columns="colCols" size="small" :pagination="false" row-key="name" />
        <h4>预览</h4>
        <p v-if="access.decision === 'forbid'" class="hint">禁止查询，不返回结果。</p>
        <template v-else-if="access.decision === 'approval' && !approved">
          <a-button type="primary" @click="askPreview">申请预览</a-button>
        </template>
        <a-table v-else :data-source="preview" :columns="previewCols" size="small" :pagination="false" :row-key="rowKey" />
      </template>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Modal } from 'ant-design-vue';
import GradeTag from '../../components/GradeTag.vue';
import PageHeader from '../../components/PageHeader.vue';
import { hydrateLayerRule } from '../../config/layerPolicies';
import { decisionLabel, evaluateAccess } from '../../engine/access';
import { previewRows } from '../../engine/metrics';
import {
  projectGrades,
  projectLayerRules,
  projectServeFolders,
  projectServeMetrics,
  projectTables,
} from '../../stores/app';
import type { FactoryConfig, WarehouseTable } from '../../types';

const router = useRouter();
const tab = ref('tables');
const drawer = ref(false);
const approved = ref(false);
const currentTable = ref<WarehouseTable>();

const tables = computed(() =>
  projectTables.value.filter((t) => ['DWD', 'DWS', 'ADS'].includes(t.layer))
);
const tableCols = [
  { title: '表名', dataIndex: 'name' },
  { title: '分层', dataIndex: 'layer', width: 80 },
  { title: '定义', dataIndex: 'comment' },
  { title: '主题域', dataIndex: 'domain', width: 90 },
  { title: '粒度', dataIndex: 'grain', width: 100 },
  { title: '等级', key: 'grade', width: 160 },
  { title: '', key: 'action', width: 100 },
];
const colCols = [
  { title: '字段', dataIndex: 'name', width: 160 },
  { title: '类型', dataIndex: 'type', width: 140 },
  { title: '注释', dataIndex: 'comment' },
];

const metricRows = computed(() =>
  projectServeMetrics.value.filter((m) => {
    if (tab.value === 'public') return m.scope === 'public' && m.status === 'published';
    return m.scope === 'business' && m.status === 'published';
  })
);
const metricCols = [
  { title: '指标', dataIndex: 'name', width: 160 },
  { title: '分类', key: 'folder', width: 110 },
  { title: '定义', dataIndex: 'definition' },
  { title: '来源表', dataIndex: 'sourceTable', width: 200 },
  { title: 'Owner', dataIndex: 'owner', width: 100 },
  { title: '状态', key: 'status', width: 90 },
];

function folderName(id: string) {
  return projectServeFolders.value.find((f) => f.id === id)?.name ?? '—';
}

const layerRule = computed(() => {
  const layer = currentTable.value?.layer;
  const r = projectLayerRules.value.find((x) => x.layer === layer);
  return r ? hydrateLayerRule(r) : undefined;
});
const access = computed(() =>
  evaluateAccess({
    table: currentTable.value,
    layerRule: layerRule.value,
    grades: projectGrades.value,
    action: 'query',
  })
);
const previewCfg = computed<FactoryConfig>(() => {
  const t = currentTable.value;
  const dims = (t?.columns ?? []).filter((c) => ['dt', 'user_type'].includes(c.name)).map((c) => c.name);
  const m = t?.columns.find((c) => /gmv|amt|cnt/.test(c.name));
  return {
    layer: (t?.layer === 'DWS' ? 'DWS' : 'DWD') as FactoryConfig['layer'],
    table: t?.name ?? '',
    dimensions: dims.length ? dims : ['dt'],
    measures: m ? [{ field: m.name, agg: 'SUM', alias: m.name }] : [],
    filters: [],
    timeGrain: 'day',
    timeRange: '近7天',
    nullFill: false,
    yoy: false,
  };
});
const preview = computed(() => previewRows(previewCfg.value));
const previewCols = computed(() => {
  const dims = previewCfg.value.dimensions.map((d) => ({ title: d, dataIndex: d, key: d }));
  const ms = previewCfg.value.measures.map((m) => ({ title: m.alias, dataIndex: m.alias, key: m.alias }));
  return [...dims, ...ms];
});

function rowKey(r: Record<string, string | number>) {
  return Object.values(r).join('|');
}

function openTable(t: WarehouseTable) {
  currentTable.value = t;
  approved.value = false;
  drawer.value = true;
}

function askPreview() {
  Modal.confirm({
    title: '该表预览需审批',
    content: access.value.reasons.join('；'),
    okText: '提交审批并查看',
    onOk() {
      approved.value = true;
    },
  });
}
</script>

<style scoped>
.hint { color: #64748b; font-size: 12px; margin: 0 12px 12px; }
.mb { margin-bottom: 12px; }
h4 { margin: 12px 0 8px; font-size: 13px; }
</style>
