<template>
  <div class="page">
    <PageHeader title="指标消费反向建模" subtitle="查询日志 → SQL 指纹 → Jaccard 聚类 → 成本收益评分 → 人工审核 → 灰度切量 → 自动路由。">
      <template #actions>
        <a-button @click="refreshClusters">重算聚类</a-button>
      </template>
    </PageHeader>

    <a-tabs v-model:activeKey="tab">
      <a-tab-pane key="logs" tab="查询日志">
        <a-table :data-source="logs" :columns="logCols" size="small" :pagination="{ pageSize: 8 }" row-key="id" class="card card-flush">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'ms'">{{ record.executionTimeMs }} ms</template>
            <template v-else-if="column.key === 'dims'">{{ record.dimensions.join(', ') }}</template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="clusters" tab="查询簇">
        <div v-for="c in clusters" :key="c.id" class="card mb">
          <div class="row">
            <b>{{ c.id }}</b>
            <a-tag>{{ c.queryIds.length }} 条相似查询</a-tag>
            <span class="muted">月均 {{ c.monthlyCount }} 次 · 平均 {{ c.avgMs }} ms</span>
          </div>
          <p>共同特征：{{ c.dimensions.join(' × ') }} + {{ c.measures.map((m) => `${m.agg}(${m.field})`).join(', ') }}</p>
          <p>建议：{{ c.suggestion }}</p>
        </div>
      </a-tab-pane>

      <a-tab-pane key="rec" tab="物化决策">
        <div v-for="r in recs" :key="r.id" class="card mb">
          <div class="row">
            <b>{{ r.targetTable }}</b>
            <a-tag :color="actionColor[r.action]">{{ actionLabel[r.action] }}</a-tag>
            <a-tag>{{ r.status }}</a-tag>
            <span>综合 <b>{{ r.scores.total }}</b></span>
          </div>
          <p class="muted">{{ decide(r) }} · 预计节省 {{ r.roi?.savedCompute }} / 存储 {{ r.roi?.storageCost }}</p>
          <div class="scores">
            <span>频次 {{ r.scores.frequency }}</span>
            <span>计算 {{ r.scores.compute }}</span>
            <span>时效 {{ r.scores.freshness }}</span>
            <span>存储成本 {{ r.scores.storage }}</span>
            <span>复用 {{ r.scores.reuse }}</span>
          </div>
          <SqlBlock :text="r.precomputeSql" />
          <div class="btns">
            <template v-if="r.status === 'pending'">
              <a-button @click="decideRec(r.id, 'rejected')">拒绝</a-button>
              <a-button type="primary" @click="decideRec(r.id, 'approved')">审核通过</a-button>
            </template>
            <template v-else-if="r.status === 'approved' || r.status === 'gray' || r.status === 'online'">
              <span>灰度 {{ r.grayPercent }}%</span>
              <a-slider :value="r.grayPercent" :marks="{ 10: '10%', 50: '50%', 100: '100%' }" :step="10" style="width: 280px" @change="(v: unknown) => setGray(r.id, Number(v))" />
            </template>
          </div>
        </div>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import PageHeader from '../components/PageHeader.vue';
import SqlBlock from '../components/SqlBlock.vue';
import { decide } from '../engine/materialize';
import { decideRec, projectClusters, projectLogs, projectRecs, refreshClusters, setGray } from '../stores/app';

const tab = ref('rec');
const logs = projectLogs;
const clusters = projectClusters;
const recs = projectRecs;
const actionLabel = { create: '新建物化表', extend: '扩展现有 DWS', retire: '建议下线' };
const actionColor = { create: 'blue', extend: 'gold', retire: 'default' };

const logCols = [
  { title: 'ID', dataIndex: 'id', width: 90 },
  { title: '时间', dataIndex: 'timestamp', width: 170 },
  { title: '源表', dataIndex: 'datasource', width: 220 },
  { title: '维度', key: 'dims' },
  { title: '耗时', key: 'ms', width: 100 },
  { title: '扫描行数', dataIndex: 'scanRows', width: 120 },
  { title: '成本', dataIndex: 'costScore', width: 80 },
];
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.row { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; }
.scores { display: flex; gap: 12px; font-size: 12px; color: #64748b; margin: 8px 0; }
.btns { display: flex; gap: 12px; align-items: center; margin-top: 10px; }
p { margin: 4px 0; }
</style>
