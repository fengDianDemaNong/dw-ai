<template>
  <div class="page">
    <PageHeader title="SQL 解析" subtitle="解析字段级作业血缘并保存版本。需要血缘分析或目录管理员。草稿逻辑表不会作为元数据。" />
    <ProductEmbed label="元数据" hint="解析走独立服务。智仓只注入租户 / 项目上下文。">
      <a-alert v-if="!canWriteMetadata" type="warning" show-icon class="mb" message="当前角色只能看，不能保存解析结果。用李四（血缘分析）或张三（项目管理员）可保存。" />
      <a-form layout="vertical">
        <a-form-item label="方言">
          <a-select v-model:value="dbType" style="width: 200px" :options="[{ value: 'hive', label: 'Hive' }, { value: 'spark', label: 'Spark' }]" />
        </a-form-item>
        <a-form-item label="SQL">
          <a-textarea v-model:value="sql" :rows="8" />
        </a-form-item>
        <a-space>
          <a-button type="primary" @click="run">解析</a-button>
          <a-button :disabled="!canWriteMetadata || !done" @click="saved = true">保存为当前版本</a-button>
        </a-space>
      </a-form>
      <div v-if="done" class="card mt">
        <h3>解析结果（演示）</h3>
        <p>dws_order_sum.gmv ← dwd_order_item.order_pay_amt</p>
        <p>dws_order_sum.order_cnt ← dwd_order_item.order_id</p>
        <p class="muted">未解析表：无。临时表已按规则穿透。</p>
        <a-tag v-if="saved" color="green">已保存（演示，不写真实库）</a-tag>
      </div>
    </ProductEmbed>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import PageHeader from '../../components/PageHeader.vue';
import ProductEmbed from '../../components/ProductEmbed.vue';
import { canWriteMetadata } from '../../stores/app';

const dbType = ref('hive');
const sql = ref(`insert into dws.dws_order_sum
select dt, user_type, item_category, count(distinct order_id), sum(order_pay_amt)
from dwd.dwd_order_item
group by dt, user_type, item_category;`);
const done = ref(false);
const saved = ref(false);

function run() {
  done.value = true;
  saved.value = false;
}
</script>

<style scoped>
.mb {
  margin-bottom: 12px;
}
.mt {
  margin-top: 16px;
}
.muted {
  color: var(--muted);
  font-size: 13px;
}
</style>
