<template>
  <div class="page">
    <PageHeader
      title="服务注册"
      subtitle="产品进程启动后向组织平台心跳登记。这里只看建模、元数据等产品地址，调度和数仓引擎是租户自己的计算资源。"
    >
      <template #actions>
        <a-button @click="load">刷新</a-button>
        <a-button type="primary" @click="open = true">手工登记</a-button>
      </template>
    </PageHeader>

    <a-table :data-source="rows" :columns="cols" row-key="product" :pagination="false" size="small" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'product'">{{ productLabel(record.product) }}</template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="record.status === 'online' ? 'green' : 'orange'">
            {{ record.status === 'online' ? '在线' : '已登记（心跳过期）' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'act'">
          <a-button size="small" danger @click="remove(record.product)">移除</a-button>
        </template>
      </template>
    </a-table>
    <p v-if="!rows.length" class="muted">还没有产品登记。先启动仓建设（18081）和血缘（18082），它们会向本进程心跳。</p>

    <a-modal v-model:open="open" title="登记服务" ok-text="登记" :confirm-loading="busy" @ok="submit">
      <a-form layout="vertical">
        <a-form-item label="产品" required>
          <a-select v-model:value="form.product" :options="productOpts" />
        </a-form-item>
        <a-form-item label="API 基址" required>
          <a-input v-model:value="form.baseUrl" placeholder="http://127.0.0.1:18081" />
        </a-form-item>
        <a-form-item label="版本">
          <a-input v-model:value="form.version" placeholder="0.1.5" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { message } from 'ant-design-vue';
import { api } from '../../api/client';
import PageHeader from '../../components/PageHeader.vue';

interface ServiceRow {
  product: string;
  version?: string;
  baseUrl: string;
  seenAt?: string;
  status?: string;
}

const rows = ref<ServiceRow[]>([]);
const open = ref(false);
const busy = ref(false);
const form = reactive({
  product: 'warehouse',
  baseUrl: 'http://127.0.0.1:18081',
  version: '0.1.5',
});

const productOpts = [
  { value: 'warehouse', label: '仓建设' },
  { value: 'metadata', label: '元数据 / 血缘' },
  { value: 'quality', label: '数据质量' },
  { value: 'serve', label: '数据服务' },
];

const cols = [
  { title: '产品', key: 'product', width: 140 },
  { title: '地址', dataIndex: 'baseUrl' },
  { title: '版本', dataIndex: 'version', width: 90 },
  { title: '最近心跳', dataIndex: 'seenAt', width: 220 },
  { title: '状态', key: 'status', width: 160 },
  { title: '', key: 'act', width: 90 },
];

function productLabel(code: string) {
  return productOpts.find((p) => p.value === code)?.label ?? code;
}

async function load() {
  try {
    rows.value = await api.platform.services();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  }
}

async function submit() {
  if (!form.product.trim() || !form.baseUrl.trim()) {
    message.warning('请填写产品和地址');
    return;
  }
  busy.value = true;
  try {
    await api.platform.registerService({
      product: form.product.trim(),
      baseUrl: form.baseUrl.trim(),
      version: form.version.trim(),
    });
    open.value = false;
    message.success('已登记');
    await load();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

async function remove(product: string) {
  try {
    await api.platform.removeService(product);
    await load();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  }
}

onMounted(load);
</script>
