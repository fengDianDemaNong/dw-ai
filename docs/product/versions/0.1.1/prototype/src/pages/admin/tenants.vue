<template>
  <div class="page">
    <PageHeader title="租户管理" subtitle="开停租户、勾模块、指定该租户管理员。进入租户必须使用租户生成的授权码。种子演示码：XINGHE-DEMO。">
      <template #actions>
        <a-button type="primary" @click="openCreate = true">新建租户</a-button>
      </template>
    </PageHeader>

    <a-table :data-source="app.tenants" :columns="tenantCols" row-key="id" :pagination="false" size="small" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'">
          <a-tag :color="record.status === 'active' ? 'green' : 'default'">
            {{ record.status === 'active' ? '使用中' : '已停用' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'modules'">
          <a-tag v-for="m in record.modules" :key="m">{{ moduleLabel(m) }}</a-tag>
        </template>
        <template v-else-if="column.key === 'access'">
          <a-tag :color="hasPlatformTenantAccess(record.id) ? 'green' : 'orange'">
            {{ hasPlatformTenantAccess(record.id) ? '已授权' : '未授权' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'act'">
          <a-space>
            <a-button size="small" @click="tryEnter(record)">进入</a-button>
            <a-button size="small" @click="edit(record)">模块 / 管理员</a-button>
            <a-button size="small" @click="toggle(record)">
              {{ record.status === 'active' ? '停用' : '启用' }}
            </a-button>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="openCreate" title="新建租户" ok-text="创建" @ok="submitCreate">
      <a-form layout="vertical">
        <a-form-item label="租户编码" required>
          <a-input v-model:value="createForm.code" placeholder="xinghe" />
        </a-form-item>
        <a-form-item label="租户名称" required>
          <a-input v-model:value="createForm.name" placeholder="星河电商" />
        </a-form-item>
        <a-form-item label="开通模块">
          <a-checkbox-group v-model:value="createForm.modules" :options="MODULE_OPTIONS" />
        </a-form-item>
        <a-form-item label="租户管理员">
          <a-radio-group v-model:value="createForm.adminMode">
            <a-radio value="existing">指定已有账号</a-radio>
            <a-radio value="new">当场新建</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="createForm.adminMode === 'existing'" label="账号">
          <a-select v-model:value="createForm.userId" placeholder="选择账号" :options="accountOpts" />
        </a-form-item>
        <template v-else>
          <a-form-item label="用户名" required>
            <a-input v-model:value="createForm.username" />
          </a-form-item>
          <a-form-item label="显示名">
            <a-input v-model:value="createForm.displayName" />
          </a-form-item>
          <a-form-item label="初始密码" required>
            <a-input-password v-model:value="createForm.password" />
          </a-form-item>
        </template>
      </a-form>
    </a-modal>

    <a-modal v-model:open="openEdit" title="模块与管理员" ok-text="保存" @ok="submitEdit">
      <a-form v-if="editing" layout="vertical">
        <a-form-item :label="`模块 · ${editing.name}`">
          <a-checkbox-group v-model:value="editForm.modules" :options="MODULE_OPTIONS" />
        </a-form-item>
        <a-form-item label="指定 / 新建管理员">
          <a-radio-group v-model:value="editForm.adminMode">
            <a-radio value="existing">已有账号</a-radio>
            <a-radio value="new">新建账号</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="editForm.adminMode === 'existing'" label="账号">
          <a-select v-model:value="editForm.userId" :options="accountOpts" />
        </a-form-item>
        <template v-else>
          <a-form-item label="用户名">
            <a-input v-model:value="editForm.username" />
          </a-form-item>
          <a-form-item label="显示名">
            <a-input v-model:value="editForm.displayName" />
          </a-form-item>
          <a-form-item label="密码">
            <a-input-password v-model:value="editForm.password" />
          </a-form-item>
        </template>
      </a-form>
    </a-modal>

    <a-modal v-model:open="openRedeem" title="输入租户授权码" ok-text="授权并进入" @ok="submitRedeem">
      <p class="lead">{{ redeeming?.name }} 未授权你进入。请向该租户管理员索取授权码（永久或限时）。</p>
      <a-input v-model:value="redeemCode" placeholder="例如 XINGHE-DEMO" @pressEnter="submitRedeem" />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import PageHeader from '../../components/PageHeader.vue';
import { ALL_MODULES, MODULE_OPTIONS } from '../../config/iam';
import type { ProductModule, Tenant } from '../../types';
import {
  app,
  createTenant,
  hasPlatformTenantAccess,
  redeemTenantGrant,
  resolveTenantHome,
  setTenantAdmin,
  setTenantModules,
  setTenantStatus,
  switchTenant,
} from '../../stores/app';

const router = useRouter();
const openCreate = ref(false);
const openEdit = ref(false);
const openRedeem = ref(false);
const editing = ref<Tenant | null>(null);
const redeeming = ref<Tenant | null>(null);
const redeemCode = ref('XINGHE-DEMO');

const createForm = reactive({
  code: '',
  name: '',
  modules: [...ALL_MODULES] as ProductModule[],
  adminMode: 'existing' as 'existing' | 'new',
  userId: app.accounts.find((a) => !a.platformAdmin)?.id ?? '',
  username: '',
  displayName: '',
  password: '123456',
});

const editForm = reactive({
  modules: [] as ProductModule[],
  adminMode: 'existing' as 'existing' | 'new',
  userId: '',
  username: '',
  displayName: '',
  password: '123456',
});

const accountOpts = computed(() =>
  app.accounts
    .filter((a) => !a.platformAdmin)
    .map((a) => ({ value: a.id, label: `${a.displayName}（${a.username}）` }))
);

const tenantCols = [
  { title: '租户', dataIndex: 'name' },
  { title: '编码', dataIndex: 'code', width: 120 },
  { title: '管理员', dataIndex: 'owner', width: 100 },
  { title: '状态', key: 'status', width: 90 },
  { title: '授权', key: 'access', width: 90 },
  { title: '模块', key: 'modules' },
  { title: '操作', key: 'act', width: 240 },
];

function moduleLabel(m: ProductModule) {
  return MODULE_OPTIONS.find((x) => x.value === m)?.label ?? m;
}

function tryEnter(t: Tenant) {
  if (hasPlatformTenantAccess(t.id)) {
    if (!switchTenant(t.id)) return;
    router.push(resolveTenantHome());
    return;
  }
  redeeming.value = t;
  redeemCode.value = t.id === 't-xinghe' ? 'XINGHE-DEMO' : '';
  openRedeem.value = true;
}

function submitRedeem() {
  if (!redeeming.value) return;
  if (!redeemTenantGrant(redeeming.value.id, redeemCode.value)) return;
  if (!switchTenant(redeeming.value.id)) return;
  openRedeem.value = false;
  router.push(resolveTenantHome());
}

function toggle(t: Tenant) {
  setTenantStatus(t.id, t.status === 'active' ? 'disabled' : 'active');
}

function edit(t: Tenant) {
  editing.value = t;
  editForm.modules = [...t.modules];
  editForm.adminMode = 'existing';
  editForm.userId = app.accounts.find((a) => a.displayName === t.owner && !a.platformAdmin)?.id ?? '';
  editForm.username = '';
  editForm.displayName = '';
  editForm.password = '123456';
  openEdit.value = true;
}

function submitCreate() {
  const t = createTenant({
    code: createForm.code,
    name: createForm.name,
    modules: createForm.modules,
    admin:
      createForm.adminMode === 'existing'
        ? { mode: 'existing', userId: createForm.userId }
        : {
            mode: 'new',
            username: createForm.username,
            displayName: createForm.displayName,
            password: createForm.password,
          },
  });
  if (!t) return;
  openCreate.value = false;
  createForm.code = '';
  createForm.name = '';
}

function submitEdit() {
  if (!editing.value) return;
  setTenantModules(editing.value.id, editForm.modules);
  if (editForm.adminMode === 'existing' && editForm.userId) {
    setTenantAdmin(editing.value.id, { mode: 'existing', userId: editForm.userId });
  }
  if (editForm.adminMode === 'new' && editForm.username) {
    setTenantAdmin(editing.value.id, {
      mode: 'new',
      username: editForm.username,
      displayName: editForm.displayName,
      password: editForm.password,
    });
  }
  openEdit.value = false;
}
</script>

<style scoped>
.lead {
  color: var(--muted);
  font-size: 13px;
  margin-bottom: 12px;
}
</style>
