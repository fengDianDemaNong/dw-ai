<template>
  <div class="page">
    <PageHeader title="数据目录" subtitle="每个项目有且仅有一个默认目录。建表语句没写目录时落到它。" />
    <ProductEmbed label="元数据" hint="需目录管理员。项目管理员自动具备。">
      <a-alert v-if="!canAdminMetadata" type="info" show-icon class="mb" message="当前不是目录管理员，只能看。李四是血缘分析，改不了这里。" />
      <a-table :data-source="rows" :columns="cols" row-key="name" size="small" :pagination="false" class="card card-flush">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'def'">
            <a-tag v-if="record.def" color="cyan">默认</a-tag>
          </template>
        </template>
      </a-table>
    </ProductEmbed>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '../../components/PageHeader.vue';
import ProductEmbed from '../../components/ProductEmbed.vue';
import { canAdminMetadata } from '../../stores/app';

const rows = [
  { name: 'hive_prod', comment: '生产 Hive', def: true },
  { name: 'hive_test', comment: '测试 Hive', def: false },
];
const cols = [
  { title: '目录名', dataIndex: 'name' },
  { title: '说明', dataIndex: 'comment' },
  { title: '', key: 'def', width: 80 },
];
</script>

<style scoped>
.mb {
  margin-bottom: 12px;
}
</style>
