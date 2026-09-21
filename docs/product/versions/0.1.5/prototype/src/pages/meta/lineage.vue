<template>
  <div class="page">
    <PageHeader title="作业血缘" subtitle="从已保存的 INSERT / CTAS 解析出来，不是建模里的来源声明。两张图不互相覆盖。">
      <template #actions>
        <a-button @click="router.push('/app/model/lineage')">打开设计态血缘</a-button>
      </template>
    </PageHeader>
    <ProductEmbed label="元数据" hint="图来自 SQL 解析版本，不是字段加工。">
      <p class="muted">起点：{{ start }}</p>
      <div class="card graph">
        <div class="col">
          <h4>上游（作业写入前）</h4>
          <ul>
            <li v-for="n in up" :key="n">{{ n }}</li>
          </ul>
        </div>
        <div class="arrow">→</div>
        <div class="col me">
          <h4>本表</h4>
          <p>{{ start }}</p>
        </div>
        <div class="arrow">→</div>
        <div class="col">
          <h4>下游作业表</h4>
          <ul>
            <li v-for="n in down" :key="n">{{ n }}</li>
          </ul>
        </div>
      </div>
      <p class="muted">列级示例：{{ start }}.gmv ← hive_prod.dwd.dwd_order_item.order_pay_amt（SUM）</p>
    </ProductEmbed>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import PageHeader from '../../components/PageHeader.vue';
import ProductEmbed from '../../components/ProductEmbed.vue';

const route = useRoute();
const router = useRouter();
const start = computed(
  () => (typeof route.query.start === 'string' && route.query.start) || 'hive_prod.dws.dws_order_sum'
);
const up = ['hive_prod.dwd.dwd_order_item', 'hive_prod.dwd.dwd_user'];
const down = ['hive_prod.ads.ads_sale_overview'];
</script>

<style scoped>
.muted {
  color: var(--muted);
  font-size: 13px;
}
.graph {
  display: flex;
  align-items: stretch;
  gap: 12px;
  padding: 16px;
}
.col {
  flex: 1;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
}
.col.me {
  border-color: var(--primary);
}
.arrow {
  align-self: center;
  color: var(--muted);
}
ul {
  margin: 0;
  padding-left: 18px;
}
</style>
