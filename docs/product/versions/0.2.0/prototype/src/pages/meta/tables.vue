<template>
  <div class="page">
    <PageHeader title="元数据目录" subtitle="物理表结构。与仓建设逻辑表分开：这里是引擎里有什么，不是字段怎么算。">
      <template #actions>
        <a-tag color="cyan">hive_prod · 默认目录</a-tag>
      </template>
    </PageHeader>
    <ProductEmbed label="元数据" hint="基址已接入。租户 xinghe / 项目 trade_dw。侧栏已去掉对方自己的壳。">
      <div class="card card-flush">
        <a-table :data-source="rows" :columns="cols" row-key="id" size="small" :pagination="false">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'bind'">
              <a-tag v-if="record.bound" color="green">已绑逻辑表</a-tag>
              <a-tag v-else>仅物理</a-tag>
            </template>
            <template v-else-if="column.key === 'act'">
              <a-button size="small" type="link" @click="router.push(`/app/meta/lineage?start=${record.full}`)">作业血缘</a-button>
            </template>
          </template>
        </a-table>
      </div>
    </ProductEmbed>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import PageHeader from '../../components/PageHeader.vue';
import ProductEmbed from '../../components/ProductEmbed.vue';
import { projectTables } from '../../stores/app';

const router = useRouter();
const rows = computed(() =>
  projectTables.value.map((t) => ({
    id: t.id,
    full: `hive_prod.${t.layer.toLowerCase()}.${t.name}`,
    comment: t.comment,
    cols: t.columns.length,
    bound: t.status === 'published' || t.status === 'draft',
  }))
);
const cols = [
  { title: '物理名 catalog.schema.table', dataIndex: 'full' },
  { title: '中文名', dataIndex: 'comment' },
  { title: '列数', dataIndex: 'cols', width: 80 },
  { title: '与建模', key: 'bind', width: 120 },
  { title: '', key: 'act', width: 120 },
];
</script>
