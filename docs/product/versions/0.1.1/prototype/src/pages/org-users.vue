<template>
  <div class="page">
    <PageHeader
      title="用户管理"
      :subtitle="
        multi
          ? '开账号、停用、重置密码，并指定谁是某项目的管理员。建模 / 只读由项目管理员在项目内派。'
          : '开账号、停用、重置密码，并指定项目管理员。'
      "
    >
      <template #actions>
        <a-button v-if="isRealTenantAdmin && multi" @click="openTransfer = true">转让管理员</a-button>
        <a-button v-if="isTenantAdmin" type="primary" @click="openCreate = true">新建用户</a-button>
      </template>
    </PageHeader>

    <section v-if="multi && isRealTenantAdmin" class="block">
      <div class="row-head">
        <h3>平台授权码</h3>
        <a-space>
          <a-button size="small" @click="makeGrant('permanent')">生成永久码</a-button>
          <a-button size="small" @click="makeGrant('timed', 1)">1 小时</a-button>
          <a-button size="small" @click="makeGrant('timed', 24)">1 天</a-button>
          <a-button size="small" @click="makeGrant('timed', 168)">7 天</a-button>
        </a-space>
      </div>
      <a-table :data-source="grantRows" :columns="grantCols" row-key="id" :pagination="false" size="small" class="card">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'kind'">
            {{ record.kind === 'permanent' ? '永久' : '限时' }}
          </template>
          <template v-else-if="column.key === 'ok'">
            <a-tag :color="record.ok ? 'green' : 'default'">{{ record.ok ? '有效' : '已失效' }}</a-tag>
          </template>
          <template v-else-if="column.key === 'act'">
            <a-button v-if="record.ok" size="small" @click="revokeTenantGrant(record.id)">作废</a-button>
          </template>
        </template>
      </a-table>
    </section>

    <a-table :data-source="rows" :columns="cols" row-key="id" :pagination="false" size="small" class="card">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'role'">
          <a-select
            v-if="isTenantAdmin"
            :value="record.role"
            :options="tenantRoleOpts"
            style="width: 140px"
            @change="(v: unknown) => setUserTenantRole(record.id, v as TenantOrgRole)"
          />
          <span v-else>{{ TENANT_ROLE_LABEL[record.role as TenantOrgRole] }}</span>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="record.status === 'active' ? 'green' : 'default'">
            {{ record.status === 'active' ? '正常' : '停用' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'projects'">
          {{ record.projects }}
        </template>
        <template v-else-if="column.key === 'act'">
          <a-space v-if="isTenantAdmin">
            <a-button size="small" @click="openAssign(record)">指定项目管理员</a-button>
            <a-button size="small" @click="openReset(record)">重置密码</a-button>
            <a-button size="small" @click="setAccountStatus(record.id, record.status === 'active' ? 'disabled' : 'active')">
              {{ record.status === 'active' ? '停用' : '启用' }}
            </a-button>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="openCreate" title="新建 / 拉入用户" ok-text="加入" @ok="submitCreate">
      <a-form layout="vertical">
        <a-form-item v-if="multi" label="方式">
          <a-radio-group v-model:value="createForm.mode">
            <a-radio value="new">新建账号</a-radio>
            <a-radio value="existing">拉入已有账号</a-radio>
          </a-radio-group>
        </a-form-item>
        <template v-if="createForm.mode === 'new'">
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
        <a-form-item v-else-if="multi" label="账号">
          <a-select
            v-model:value="createForm.existingUserId"
            placeholder="其它租户的账号"
            :options="outsiderOpts"
          />
        </a-form-item>
        <a-form-item v-if="multi" label="租户角色">
          <a-select v-model:value="createForm.role" :options="tenantRoleOpts" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="assignOpen" title="指定项目管理员" ok-text="指定" @ok="submitAssign">
      <p class="lead">把 {{ assigning?.displayName }} 设为所选项目的管理员。拉建模 / 只读请让项目管理员在项目内操作。</p>
      <a-form layout="vertical">
        <a-form-item label="项目">
          <a-select v-model:value="assignForm.projectId" :options="projectOpts" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="resetOpen" title="重置密码" ok-text="重置" @ok="submitReset">
      <a-form layout="vertical">
        <a-form-item :label="resetting ? `新密码 · ${resetting.displayName}` : '新密码'">
          <a-input-password v-model:value="newPassword" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="openTransfer" title="转让租户管理员" ok-text="转让" @ok="submitTransfer">
      <p class="lead">转让后你降为普通成员，对方成为本租户唯一走转让路径的现任管理员。</p>
      <a-select v-model:value="transferTo" placeholder="选择本租户用户" :options="transferOpts" style="width: 100%" />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import PageHeader from '../components/PageHeader.vue';
import { PROJECT_ROLE_LABEL, TENANT_ROLE_LABEL } from '../config/iam';
import { isMultiTenant } from '../config/runtime';
import type { Account, GrantKind, TenantOrgRole } from '../types';
import {
  accountsNotInTenant,
  app,
  appointProjectAdmin,
  createOrgUser,
  createTenantGrant,
  grantIsValid,
  isRealTenantAdmin,
  isTenantAdmin,
  resetAccountPassword,
  revokeTenantGrant,
  sessionAccount,
  setAccountStatus,
  setUserTenantRole,
  tenantGrants,
  tenantRoleOf,
  tenantUsers,
  transferTenantAdmin,
} from '../stores/app';

const multi = isMultiTenant();
const openCreate = ref(false);
const openTransfer = ref(false);
const transferTo = ref<string>();
const assignOpen = ref(false);
const resetOpen = ref(false);
const assigning = ref<Account | null>(null);
const resetting = ref<Account | null>(null);
const newPassword = ref('123456');

const createForm = reactive({
  mode: 'new' as 'new' | 'existing',
  username: '',
  displayName: '',
  password: '123456',
  existingUserId: '',
  role: 'member' as TenantOrgRole,
});

const assignForm = reactive({
  projectId: '',
});

const tenantRoleOpts = (Object.keys(TENANT_ROLE_LABEL) as TenantOrgRole[]).map((v) => ({
  value: v,
  label: TENANT_ROLE_LABEL[v],
}));

const projectOpts = computed(() =>
  app.projects
    .filter((p) => p.tenantId === app.currentTenantId)
    .map((p) => ({ value: p.id, label: p.name }))
);

const outsiderOpts = computed(() =>
  accountsNotInTenant().map((a) => ({ value: a.id, label: `${a.displayName}（${a.username}）` }))
);

const rows = computed(() =>
  tenantUsers().map((u) => {
    const projects = app.projects
      .filter((p) => p.tenantId === app.currentTenantId)
      .filter((p) => app.members.some((m) => m.projectId === p.id && m.userId === u.id))
      .map((p) => {
        const role = app.members.find((m) => m.projectId === p.id && m.userId === u.id)?.role;
        return `${p.name}（${role ? PROJECT_ROLE_LABEL[role] : ''}）`;
      })
      .join('、');
    return {
      ...u,
      role: tenantRoleOf(u.id) ?? 'member',
      projects: projects || '未派进项目',
    };
  })
);

const cols = computed(() => {
  const base: { title: string; dataIndex?: string; key?: string; width?: number }[] = [
    { title: '显示名', dataIndex: 'displayName' },
    { title: '用户名', dataIndex: 'username' },
  ];
  if (multi) base.push({ title: '租户角色', key: 'role', width: 160 });
  base.push(
    { title: '状态', key: 'status', width: 80 },
    { title: '项目', key: 'projects' },
    { title: '操作', key: 'act', width: 300 }
  );
  return base;
});

const grantRows = computed(() =>
  tenantGrants().map((g) => ({
    ...g,
    ok: grantIsValid(g),
    expires: g.expiresAt ? g.expiresAt.replace('T', ' ').slice(0, 16) : '—',
  }))
);
const grantCols = [
  { title: '授权码', dataIndex: 'code' },
  { title: '种类', key: 'kind', width: 80 },
  { title: '到期', dataIndex: 'expires', width: 150 },
  { title: '状态', key: 'ok', width: 80 },
  { title: '操作', key: 'act', width: 80 },
];

const transferOpts = computed(() =>
  tenantUsers()
    .filter((u) => u.id !== sessionAccount.value?.id && u.status === 'active')
    .map((u) => ({ value: u.id, label: `${u.displayName}（${u.username}）` }))
);

function submitCreate() {
  const acc = createOrgUser({
    username: createForm.username,
    displayName: createForm.displayName,
    password: createForm.password,
    role: createForm.role,
    existingUserId: createForm.mode === 'existing' ? createForm.existingUserId : undefined,
  });
  if (!acc) return;
  openCreate.value = false;
  createForm.username = '';
  createForm.displayName = '';
}

function openAssign(u: Account) {
  assigning.value = u;
  assignForm.projectId = projectOpts.value[0]?.value ?? '';
  assignOpen.value = true;
}

function submitAssign() {
  if (!assigning.value || !assignForm.projectId) return;
  appointProjectAdmin(assigning.value.id, assignForm.projectId);
  assignOpen.value = false;
}

function openReset(u: Account) {
  resetting.value = u;
  newPassword.value = '123456';
  resetOpen.value = true;
}

function submitReset() {
  if (!resetting.value) return;
  resetAccountPassword(resetting.value.id, newPassword.value);
  resetOpen.value = false;
}

function makeGrant(kind: GrantKind, hours?: number) {
  createTenantGrant(kind, hours);
}

function submitTransfer() {
  if (!transferTo.value) return;
  if (!transferTenantAdmin(transferTo.value)) return;
  openTransfer.value = false;
}
</script>

<style scoped>
.lead {
  color: var(--muted);
  font-size: 13px;
  margin-bottom: 16px;
}

.block {
  margin-bottom: 24px;
}

.row-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.row-head h3 {
  margin: 0;
  font-size: 15px;
}
</style>
