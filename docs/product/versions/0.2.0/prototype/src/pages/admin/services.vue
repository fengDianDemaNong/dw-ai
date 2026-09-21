<template>
  <div class="page">
    <PageHeader
      title="服务注册"
      subtitle="只登记产品进程：建模、元数据、质量、数据服务。调度集群和数仓引擎是租户自己的计算资源，不在这里。"
    >
      <template #actions>
        <a-button type="primary" @click="open = true">登记服务</a-button>
      </template>
    </PageHeader>

    <p class="lead">
      给租户开通的是产品上限。DolphinScheduler、Hive / Spark 等由租户管理员在工作台「计算资源」自己登记 Token 和地址。
    </p>

    <a-table :data-source="rows" :columns="cols" row-key="id" :pagination="false" size="small" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'product'">{{ productOf(record.product).label }}</template>
        <template v-else-if="column.key === 'source'">
          {{ record.source === 'heartbeat' ? '心跳上报' : '手工登记' }}
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="record.status === 'online' ? 'green' : 'default'">
            {{ record.status === 'online' ? '在线' : '离线' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'act'">
          <a-space>
            <a-button size="small" @click="reportHeartbeat(record.id)">模拟心跳</a-button>
            <a-button size="small" @click="setServiceStatus(record.id, record.status === 'online' ? 'offline' : 'online')">
              {{ record.status === 'online' ? '下线' : '上线' }}
            </a-button>
            <a-button size="small" danger @click="removeService(record.id)">移除</a-button>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="open" title="登记服务" ok-text="登记" @ok="submit">
      <a-form layout="vertical">
        <a-form-item label="产品" required>
          <a-select v-model:value="form.product" :options="productOpts" />
        </a-form-item>
        <a-form-item label="服务名" required>
          <a-input v-model:value="form.name" placeholder="元数据 · 机房B" />
        </a-form-item>
        <a-form-item label="基址" required>
          <a-input v-model:value="form.baseUrl" placeholder="http://192.168.10.21:8080" />
        </a-form-item>
        <a-form-item label="备注">
          <a-input v-model:value="form.note" placeholder="与建模不在同一台机器" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import PageHeader from '../../components/PageHeader.vue';
import { PRODUCT_MANIFESTS, productOf } from '../../config/products';
import type { ProductModule } from '../../types';
import { platformServices, registerService, removeService, reportHeartbeat, setServiceStatus } from '../../stores/app';

const open = ref(false);
const form = reactive({
  product: 'metadata' as ProductModule,
  name: '',
  baseUrl: '',
  note: '',
});

const rows = computed(() => platformServices());
const productOpts = PRODUCT_MANIFESTS.map((p) => ({ value: p.code, label: p.label }));

const cols = [
  { title: '产品', key: 'product', width: 110 },
  { title: '服务名', dataIndex: 'name' },
  { title: '地址', dataIndex: 'baseUrl' },
  { title: '来源', key: 'source', width: 110 },
  { title: '状态', key: 'status', width: 80 },
  { title: '最近上报', dataIndex: 'lastSeenAt', width: 160 },
  { title: '备注', dataIndex: 'note' },
  { title: '操作', key: 'act', width: 240 },
];

function submit() {
  const row = registerService({
    product: form.product,
    name: form.name,
    baseUrl: form.baseUrl,
    source: 'manual',
    note: form.note,
  });
  if (!row) return;
  open.value = false;
  form.name = '';
  form.baseUrl = '';
  form.note = '';
}
</script>

<style scoped>
.lead {
  color: var(--muted);
  font-size: 13px;
  margin: 0 0 12px;
}
</style>
