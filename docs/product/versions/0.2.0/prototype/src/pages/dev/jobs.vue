<template>
  <div class="page">
    <PageHeader title="调度任务" subtitle="任务落在本组织登记的 DolphinScheduler 上。发布模型后的挂载、补数、重跑都走租户自己的集群，不经平台服务注册。" />
    <a-table :data-source="jobs" :columns="cols" row-key="id" :pagination="false" size="small" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'type'">
          <a-tag>{{ typeLabel[record.type] }}</a-tag>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="record.status === 'success' ? 'green' : record.status === 'failed' ? 'red' : 'blue'">
            {{ record.status }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'dep'">
          {{ record.dependsOn.map((id: string) => jobs.find((j) => j.id === id)?.name || id).join(' → ') || '—' }}
        </template>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '../../components/PageHeader.vue';
import { projectJobs } from '../../stores/app';

const jobs = projectJobs;
const typeLabel: Record<string, string> = {
  sync: '同步',
  etl: 'ETL',
  quality: '质量',
  materialize: '沉淀',
};
const cols = [
  { title: '任务', dataIndex: 'name' },
  { title: '类型', key: 'type', width: 90 },
  { title: '引擎', dataIndex: 'engine', width: 120 },
  { title: '依赖', key: 'dep' },
  { title: '状态', key: 'status', width: 100 },
  { title: '最近运行', dataIndex: 'lastRun', width: 160 },
  { title: '表', dataIndex: 'table', width: 240 },
];
</script>
