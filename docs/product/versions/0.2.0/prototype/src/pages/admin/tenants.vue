<template>
  <div class="page">
    <PageHeader title="租户管理" subtitle="勾选的是平台开通上限。服务地址在「服务注册」，租户内部再决定启用与谁能看见。进入租户每次须授权码。演示码：XINGHE-DEMO。">
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
          <a-tag v-for="m in record.modules" :key="m" :color="isShipped(m) ? 'cyan' : 'default'">
            {{ moduleLabel(m) }}{{ isShipped(m) ? '' : '（未上线）' }}
          </a-tag>
          <span v-if="!record.modules?.length" class="muted">未开通</span>
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

    <a-modal v-model:open="openCreate" title="新建租户" ok-text="创建" @ok="submitCreate">
      <a-form layout="vertical">
        <a-form-item label="租户编码" required>
          <a-input v-model:value="createForm.code" placeholder="xinghe" />
        </a-form-item>
        <a-form-item label="租户名称" required>
          <a-input v-model:value="createForm.name" placeholder="星河电商" />
        </a-form-item>
        <a-form-item label="开通功能" required>
          <p class="lead">勾选平台给该租户的开通上限。地址在服务注册，不在租户上。租户管理员进入后还可再关模块、限制可见范围。</p>
          <a-checkbox-group v-model:value="createForm.modules" :options="moduleOpts" class="mods" />
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

    <a-modal v-model:open="openEdit" title="编辑租户" ok-text="保存" @ok="submitEdit">
      <a-form v-if="editing" layout="vertical">
        <a-form-item label="租户编码">
          <a-input :value="editing.code" disabled />
        </a-form-item>
        <a-form-item label="租户名称" required>
          <a-input v-model:value="editForm.name" />
        </a-form-item>
        <a-form-item label="开通功能" required>
          <p class="lead">勾选平台给该租户的开通上限。地址在服务注册，不在租户上。租户管理员进入后还可再关模块、限制可见范围。</p>
          <a-checkbox-group v-model:value="editForm.modules" :options="moduleOpts" class="mods" />
        </a-form-item>
        <a-form-item label="指定管理员（已有账号）">
          <a-select v-model:value="editForm.userId" :options="accountOpts" allow-clear placeholder="不改则留空" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="openRedeem" title="输入租户授权码" ok-text="授权并进入" @ok="submitRedeem">
      <p class="lead">每次进入 {{ redeeming?.name }} 都须输入该租户签发的授权码。向租户管理员索取（含有效期与范围）。</p>
      <a-input v-model:value="redeemCode" placeholder="请输入授权码" @pressEnter="submitRedeem" />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import PageHeader from '../../components/PageHeader.vue';
import { ALL_MODULES, MODULE_OPTIONS } from '../../config/iam';
import type { ProductModule, Tenant } from '../../types';
import {
  app,
  createTenant,
  redeemTenantGrant,
  resolveTenantHome,
  setTenantAdmin,
  setTenantModules,
  setTenantName,
  setTenantStatus,
  switchTenant,
} from '../../stores/app';

const router = useRouter();
const openCreate = ref(false);
const openEdit = ref(false);
const openRedeem = ref(false);
const editing = ref<Tenant | null>(null);
const redeeming = ref<Tenant | null>(null);
const redeemCode = ref('');

const moduleOpts = MODULE_OPTIONS.map((m) => ({
  value: m.value,
  label: m.shipped === false ? `${m.label}（未上线）` : m.label,
}));

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
  name: '',
  modules: [] as ProductModule[],
  userId: '',
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
  { title: '开通功能', key: 'modules' },
  { title: '操作', key: 'act', width: 200 },
];

function moduleLabel(m: ProductModule) {
  return MODULE_OPTIONS.find((x) => x.value === m)?.label ?? m;
}

function isShipped(m: ProductModule) {
  return MODULE_OPTIONS.find((x) => x.value === m)?.shipped !== false;
}

function tryEnter(t: Tenant) {
  redeeming.value = t;
  redeemCode.value = '';
  openRedeem.value = true;
}

function submitRedeem() {
  if (!redeeming.value) return;
  if (!redeemCode.value.trim()) {
    message.warning('请输入授权码');
    return;
  }
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
  editForm.name = t.name;
  editForm.modules = [...t.modules];
  editForm.userId = '';
  openEdit.value = true;
}

function submitCreate() {
  if (!createForm.modules.length) {
    message.warning('请至少开通一项功能');
    return;
  }
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
  if (!editForm.name.trim()) {
    message.warning('请填写名称');
    return;
  }
  if (!editForm.modules.length) {
    message.warning('请至少开通一项功能');
    return;
  }
  if (!setTenantName(editing.value.id, editForm.name)) return;
  setTenantModules(editing.value.id, editForm.modules);
  if (editForm.userId) {
    setTenantAdmin(editing.value.id, { mode: 'existing', userId: editForm.userId });
  }
  openEdit.value = false;
}
</script>

<style scoped>
.lead,
.hint,
.muted {
  color: var(--muted);
  font-size: 13px;
  margin-bottom: 12px;
}
.hint {
  margin: 0 0 8px;
}
.mods {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
