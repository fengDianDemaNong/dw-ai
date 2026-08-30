<template>
  <div class="page">
    <PageHeader title="租户管理" subtitle="开停租户、指定管理员、勾选开通功能。进入租户每次都须输入该租户签发的授权码。">
      <template #actions>
        <a-button type="primary" @click="openCreate = true">新建租户</a-button>
      </template>
    </PageHeader>

    <a-table :data-source="tenants" :columns="tenantCols" row-key="id" :pagination="false" size="small" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'">
          <a-tag :color="record.status === 'active' ? 'green' : 'default'">
            {{ record.status === 'active' ? '使用中' : '已停用' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'modules'">
          <a-tag v-for="m in shippedModules(record.modules)" :key="m" :color="isShipped(m) ? 'cyan' : 'default'">
            {{ moduleLabel(m) }}
          </a-tag>
          <span v-if="!shippedModules(record.modules).length" class="muted">未开通</span>
        </template>
        <template v-else-if="column.key === 'act'">
          <a-space>
            <a-button size="small" @click="tryEnter(record)">进入</a-button>
            <a-button size="small" @click="edit(record)">编辑</a-button>
            <a-button size="small" @click="toggle(record)">
              {{ record.status === 'active' ? '停用' : '启用' }}
            </a-button>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="openCreate" title="新建租户" ok-text="创建" :confirm-loading="busy" @ok="submitCreate">
      <a-form layout="vertical">
        <a-form-item label="租户编码" required>
          <a-input v-model:value="createForm.code" placeholder="xinghe" />
        </a-form-item>
        <a-form-item label="租户名称" required>
          <a-input v-model:value="createForm.name" placeholder="星河电商" />
        </a-form-item>
        <a-form-item label="开通功能" required>
          <p class="lead">勾选该租户可以使用的功能。未上线的模块勾选后仅记开通，控制台暂不出现菜单。</p>
          <a-checkbox-group v-model:value="createForm.modules" :options="moduleOpts" class="mods" />
        </a-form-item>
        <a-form-item label="租户管理员">
          <a-radio-group v-model:value="createForm.adminMode">
            <a-radio value="existing">指定已有账号</a-radio>
            <a-radio value="new">当场新建</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="createForm.adminMode === 'existing'" label="账号">
          <a-select v-model:value="createForm.adminUserId" placeholder="选择账号" :options="accountOpts" />
        </a-form-item>
        <template v-else>
          <a-form-item label="用户名" required>
            <a-input v-model:value="createForm.adminUsername" />
          </a-form-item>
          <a-form-item label="显示名">
            <a-input v-model:value="createForm.adminDisplayName" />
          </a-form-item>
          <a-form-item label="初始密码" required>
            <a-input-password v-model:value="createForm.adminPassword" />
          </a-form-item>
        </template>
      </a-form>
    </a-modal>

    <a-modal v-model:open="openEdit" title="编辑租户" ok-text="保存" :confirm-loading="busy" @ok="submitEdit">
      <a-form v-if="editing" layout="vertical">
        <a-form-item label="租户编码">
          <a-input :value="editing.code" disabled />
        </a-form-item>
        <a-form-item label="租户名称" required>
          <a-input v-model:value="editForm.name" />
        </a-form-item>
        <a-form-item label="开通功能" required>
          <p class="lead">勾选该租户可以使用的功能。未上线的模块勾选后仅记开通，控制台暂不出现菜单。</p>
          <a-checkbox-group v-model:value="editForm.modules" :options="moduleOpts" class="mods" />
        </a-form-item>
        <a-form-item label="指定管理员（已有账号）">
          <a-select v-model:value="editForm.owner" :options="accountOpts" allow-clear placeholder="不改则留空" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="openRedeem" title="输入租户授权码" ok-text="授权并进入" :confirm-loading="busy" @ok="submitRedeem">
      <p class="lead">每次进入 {{ redeeming?.name }} 都须输入该租户签发的授权码。向租户管理员索取（含有效期与范围）。</p>
      <a-input v-model:value="redeemCode" placeholder="请输入授权码" @pressEnter="submitRedeem" />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import { api } from '../../api/client';
import PageHeader from '../../components/PageHeader.vue';
import { MODULE_OPTIONS } from '../../config/iam';
import type { ProductModule, Tenant } from '../../types';
import { resolveTenantHome, switchTenant } from '../../stores/app';

const router = useRouter();
const tenants = ref<Tenant[]>([]);
const accounts = ref<{ id: string; username: string; displayName: string }[]>([]);
const openCreate = ref(false);
const openEdit = ref(false);
const openRedeem = ref(false);
const editing = ref<Tenant | null>(null);
const redeeming = ref<Tenant | null>(null);
const redeemCode = ref('');
const busy = ref(false);

const moduleOpts = MODULE_OPTIONS.map((m) => ({
  value: m.value,
  label: m.shipped === false ? `${m.label}（未上线）` : m.label,
}));

const createForm = reactive({
  code: '',
  name: '',
  modules: ['warehouse'] as ProductModule[],
  adminMode: 'existing' as 'existing' | 'new',
  adminUserId: '',
  adminUsername: '',
  adminDisplayName: '',
  adminPassword: '123456',
});

const editForm = reactive({
  name: '',
  modules: [] as ProductModule[],
  owner: '',
});

const accountOpts = computed(() =>
  accounts.value.map((a) => ({ value: a.id, label: `${a.displayName}（${a.username}）` }))
);

const tenantCols = [
  { title: '租户', dataIndex: 'name' },
  { title: '编码', dataIndex: 'code', width: 120 },
  { title: '管理员', dataIndex: 'owner', width: 100 },
  { title: '状态', key: 'status', width: 90 },
  { title: '开通功能', key: 'modules' },
  { title: '操作', key: 'act', width: 200 },
];

function moduleLabel(m: string) {
  return MODULE_OPTIONS.find((x) => x.value === m)?.label ?? m;
}

function isShipped(m: string) {
  return MODULE_OPTIONS.find((x) => x.value === m)?.shipped !== false;
}

function shippedModules(mods?: string[]) {
  return mods ?? [];
}

async function reload() {
  tenants.value = await api.platform.tenants();
  accounts.value = await api.platform.accounts();
}

async function tryEnter(t: Tenant) {
  redeeming.value = t;
  redeemCode.value = '';
  openRedeem.value = true;
}

async function submitRedeem() {
  if (!redeeming.value) return;
  if (!redeemCode.value.trim()) {
    message.warning('请输入授权码');
    return;
  }
  busy.value = true;
  try {
    await api.enterTenant(redeeming.value.id, redeemCode.value.trim());
    if (!(await switchTenant(redeeming.value.id))) return;
    openRedeem.value = false;
    router.push(resolveTenantHome());
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

async function toggle(t: Tenant) {
  busy.value = true;
  try {
    await api.platform.patchTenant(t.id, { status: t.status === 'active' ? 'disabled' : 'active' });
    await reload();
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

function edit(t: Tenant) {
  editing.value = t;
  editForm.name = t.name;
  editForm.modules = [...(t.modules ?? [])] as ProductModule[];
  editForm.owner = '';
  openEdit.value = true;
}

async function submitCreate() {
  if (!createForm.modules.length) {
    message.warning('请至少开通一项功能');
    return;
  }
  busy.value = true;
  try {
    await api.platform.createTenant({
      code: createForm.code,
      name: createForm.name,
      modules: createForm.modules,
      adminUserId: createForm.adminMode === 'existing' ? createForm.adminUserId : undefined,
      adminUsername: createForm.adminMode === 'new' ? createForm.adminUsername : undefined,
      adminDisplayName: createForm.adminMode === 'new' ? createForm.adminDisplayName : undefined,
      adminPassword: createForm.adminMode === 'new' ? createForm.adminPassword : undefined,
    });
    openCreate.value = false;
    createForm.code = '';
    createForm.name = '';
    createForm.modules = ['warehouse'];
    await reload();
    message.success('租户已创建');
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

async function submitEdit() {
  if (!editing.value) return;
  if (!editForm.name.trim()) {
    message.warning('请填写名称');
    return;
  }
  if (!editForm.modules.length) {
    message.warning('请至少开通一项功能');
    return;
  }
  busy.value = true;
  try {
    await api.platform.patchTenant(editing.value.id, {
      name: editForm.name.trim(),
      modules: editForm.modules,
      owner: editForm.owner || undefined,
    });
    openEdit.value = false;
    await reload();
    message.success('已保存');
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

onMounted(() => {
  void reload();
});
</script>

<style scoped>
.lead {
  color: var(--muted);
  font-size: 13px;
  margin-bottom: 12px;
}

.mods {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.muted {
  color: var(--muted);
  font-size: 12px;
}
</style>
