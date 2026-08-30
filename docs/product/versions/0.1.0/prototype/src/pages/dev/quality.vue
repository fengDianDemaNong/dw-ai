<template>
  <div class="page">
    <PageHeader title="数据质量" subtitle="主键、空值、枚举、波动、格式规则由建模中心自动生成，开发中心负责调度与告警。" />
    <a-table :data-source="rules" :columns="cols" row-key="id" :pagination="false" size="small" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'">
          <a-tag :color="record.status === 'ok' ? 'green' : record.status === 'warn' ? 'orange' : record.status === 'fail' ? 'red' : 'default'">
            {{ record.status }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'type'">
          {{ labels[record.type] }}
        </template>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '../../components/PageHeader.vue';
import { projectQuality } from '../../stores/app';

const rules = projectQuality;
const labels: Record<string, string> = {
  pk_unique: '主键唯一性',
  null_rate: '空值率',
  enum: '枚举值',
  volatility: '波动率',
  format: '格式校验',
};
const cols = [
  { title: '表', dataIndex: 'table', width: 240 },
  { title: '规则', key: 'type', width: 120 },
  { title: '字段', dataIndex: 'field', width: 160 },
  { title: '逻辑', dataIndex: 'logic' },
  { title: '阈值', dataIndex: 'threshold', width: 140 },
  { title: '最近值', dataIndex: 'lastValue', width: 140 },
  { title: '状态', key: 'status', width: 90 },
];
</script>
