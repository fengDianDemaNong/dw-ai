<template>
  <div class="page">
    <PageHeader
      title="服务注册"
      subtitle="登记各产品的「页面地址」，门户据此在平台壳子里嵌入它们的界面。这里只管界面上哪儿找；后端地址已不再需要——项目同步由各服务主动来取。"
    >
      <template #actions>
        <a-button @click="load">刷新</a-button>
        <a-button type="primary" @click="open = true">登记服务</a-button>
      </template>
    </PageHeader>

    <!-- 这一页只服务**本平台自己的产品**。不写下来的话，管理员会把外部系统的地址也填进来，
         然后在「菜单管理」里看到「拉不到菜单清单」—— 那一步注定失败，且失败信息里
         看不出「这一档本来就不适用于外部系统」。外部系统走「外链菜单」，那是另一条路。 -->
    <p class="muted">
      只登记本平台自己的产品（产品码固定，就是下方那个下拉里的四个）。<b>外部系统不在这里登记</b>，
      也不需要：它们的页面不导出菜单清单（菜单由浏览器里的 JS 画出来，服务端拿不到），
      请直接在「菜单管理」里用<b>外链菜单</b>挂 —— 外链菜单只要一个地址，既不查这张表、也不查清单。
    </p>

    <a-table :data-source="rows" :columns="cols" row-key="product" :pagination="false" size="small" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'product'">{{ productLabel(record.product) }}</template>
        <template v-else-if="column.key === 'frontendUrl'">
          <span v-if="record.frontendUrl">{{ record.frontendUrl }}</span>
          <span v-else class="muted">未配置 —— 该产品的菜单点击后无处可去</span>
        </template>
        <template v-else-if="column.key === 'act'">
          <a-button size="small" danger @click="remove(record.product)">移除</a-button>
        </template>
      </template>
    </a-table>
    <p v-if="!rows.length" class="muted">
      还没有产品登记。先在这里填上数仓建模（<code>http://127.0.0.1:5172</code>）或血缘（<code>http://127.0.0.1:5173</code>）的
      <b>页面</b>地址，再去「菜单管理」把它们的页面配进侧栏。
    </p>

    <a-modal v-model:open="open" title="登记服务" ok-text="登记" :confirm-loading="busy" @ok="submit">
      <a-form layout="vertical">
        <a-form-item label="产品" required>
          <a-select v-model:value="form.product" :options="productOpts" />
          <p class="muted" style="margin: 4px 0 0">
            必须与租户许可里的模块名一致，否则菜单会被按许可过滤掉。
          </p>
        </a-form-item>
        <a-form-item label="前端地址" required>
          <a-input v-model:value="form.frontendUrl" placeholder="http://127.0.0.1:5173" />
          <p class="muted" style="margin: 4px 0 0">
            该产品<b>页面</b>的地址（开发态是 Vite 端口，生产是它的站点根），不是后端 API 地址。
          </p>
        </a-form-item>
        <a-form-item label="版本">
          <a-input v-model:value="form.version" placeholder="选填，仅作登记留痕" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { message } from 'ant-design-vue';
import { api } from '../../api/client';
import { PRODUCT_OPTIONS, productLabel } from '../../config/products';
import PageHeader from '../../components/PageHeader.vue';

interface ServiceRow {
  product: string;
  version?: string;
  frontendUrl: string;
}

const rows = ref<ServiceRow[]>([]);
const open = ref(false);
const busy = ref(false);
const form = reactive({
  product: 'warehouse',
  frontendUrl: '',
  // 版本不再是心跳自报值，改成选填的登记留痕 —— 默认值会让人以为它是必填
  version: '',
});

// 产品码与中文名在 config/products.ts 里统一维护（菜单管理页用的是同一份）
const productOpts = PRODUCT_OPTIONS;

const cols = [
  { title: '产品', key: 'product', width: 140 },
  { title: '前端地址', key: 'frontendUrl' },
  { title: '版本', dataIndex: 'version', width: 120 },
  { title: '', key: 'act', width: 90 },
];

async function load() {
  try {
    rows.value = await api.platform.services();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  }
}

async function submit() {
  if (!form.product.trim() || !form.frontendUrl.trim()) {
    message.warning('请填写产品和前端地址');
    return;
  }
  busy.value = true;
  try {
    await api.platform.registerService({
      product: form.product.trim(),
      frontendUrl: form.frontendUrl.trim(),
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
