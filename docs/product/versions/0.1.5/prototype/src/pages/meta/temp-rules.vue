<template>
  <div class="page">
    <PageHeader title="临时表规则" subtitle="命中的表在血缘图上被穿透。保存作业血缘时库里永远不存临时表。" />
    <ProductEmbed label="元数据" hint="匹配方式要显式选：通配符 tmp_* 与正则含义不同。">
      <a-alert v-if="!canAdminMetadata" type="info" show-icon class="mb" message="改规则需要目录管理员。" />
      <a-table :data-source="rows" :columns="cols" row-key="id" size="small" :pagination="false" class="card card-flush" />
    </ProductEmbed>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '../../components/PageHeader.vue';
import ProductEmbed from '../../components/ProductEmbed.vue';
import { canAdminMetadata } from '../../stores/app';

const rows = [
  { id: 1, target: '库名', pattern: 'tmp_*', mode: '通配符' },
  { id: 2, target: '表名', pattern: '_tmp$', mode: '正则' },
];
const cols = [
  { title: '作用', dataIndex: 'target', width: 100 },
  { title: '表达式', dataIndex: 'pattern' },
  { title: '匹配', dataIndex: 'mode', width: 100 },
];
</script>

<style scoped>
.mb {
  margin-bottom: 12px;
}
</style>
