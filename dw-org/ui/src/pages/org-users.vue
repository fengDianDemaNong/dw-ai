<template>
  <div class="page">
    <PageHeader
      :title="multi ? '用户管理' : '用户'"
      :subtitle="
        multi
          ? '开账号、停用、重置密码，并指定谁是某项目的管理员。建模 / 只读由项目管理员在项目内派。'
          : '开账号、停用、重置密码，并指定项目管理员。'
      "
    >
      <template #actions>
        <a-button v-if="isRealTenantAdmin && multi" @click="openTransfer = true">转让管理员</a-button>
        <a-button v-if="isTenantAdmin" type="primary" @click="openCreate = true">新增</a-button>
      </template>
    </PageHeader>

    <section v-if="multi && isRealTenantAdmin" class="block">
      <div class="row-head">
        <h3>平台授权码</h3>
        <a-button size="small" type="primary" @click="startCreateGrant">新增</a-button>
      </div>
      <a-table :data-source="grants" :columns="grantCols" row-key="id" :pagination="false" size="small" class="card grant-table">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'kind'">
            {{ kindLabel(record.kind) }}
          </template>
          <template v-else-if="column.key === 'expires'">
            {{ record.kind === 'permanent' || !record.expiresAt ? '不过期' : formatWhen(record.expiresAt) }}
          </template>
          <template v-else-if="column.key === 'scope'">
            {{ scopeLine(record) }}
          </template>
          <template v-else-if="column.key === 'aiCaps'">
            <span v-if="!record.aiCaps?.length" class="muted">不限制</span>
            <a-tag v-for="c in record.aiCaps" :key="c">{{ aiCapLabel(c as AiCap) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'ok'">
            <a-tag :color="record.valid ? 'green' : 'default'">{{ record.valid ? '有效' : '已失效' }}</a-tag>
          </template>
          <template v-else-if="column.key === 'act'">
            <a-space>
              <a-button size="small" @click="startEditGrant(record)">编辑</a-button>
              <a-button v-if="record.valid" size="small" @click="revoke(record.id)">停用</a-button>
              <a-popconfirm title="删除后不可恢复，已绑定的进入资格一并失效。" @confirm="removeGrant(record.id)">
                <a-button size="small" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </section>

    <a-table :data-source="rows" :columns="cols" row-key="id" :pagination="false" size="small" class="card org-users-table">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'role'">
          {{ TENANT_ROLE_LABEL[(record.tenantRole || 'member') as 'admin' | 'member'] }}
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="record.status === 'active' ? 'green' : 'default'">
            {{ record.status === 'active' ? '启用' : '停用' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'projects'">
          {{ projectLine(record.id) }}
        </template>
        <template v-else-if="column.key === 'act'">
          <a-space v-if="isTenantAdmin">
            <a-button size="small" @click="openEdit(record)">编辑</a-button>
            <a-popconfirm
              v-if="record.id !== sessionAccount?.id"
              title="将从本组织移除该用户（账号仍在，可再拉入）。"
              @confirm="removeUser(record)"
            >
              <a-button size="small" danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-drawer
      v-model:open="openEditUser"
      title="编辑用户"
      placement="right"
      width="480"
      :destroy-on-close="true"
      root-class-name="user-edit-drawer"
    >
      <a-form layout="vertical">
        <div class="drawer-sec">
          <h4>基本信息</h4>
          <a-form-item label="显示名" required>
            <a-input v-model:value="editForm.displayName" placeholder="显示名" />
          </a-form-item>
          <a-form-item label="用户名" required>
            <a-input v-model:value="editForm.username" placeholder="登录用户名，须全局唯一" />
          </a-form-item>
          <a-form-item v-if="multi" label="角色">
            <a-select v-model:value="editForm.tenantRole" :options="tenantRoleOpts" />
          </a-form-item>
          <a-form-item label="状态">
            <a-radio-group v-model:value="editForm.status" :disabled="editing?.id === sessionAccount?.id">
              <a-radio value="active">启用</a-radio>
              <a-radio value="disabled">停用</a-radio>
            </a-radio-group>
            <div v-if="editing?.id === sessionAccount?.id" class="hint">不能停用自己。</div>
          </a-form-item>
        </div>

        <div class="drawer-sec">
          <h4>项目</h4>
          <p class="lead">加入或移出本组织项目，并指定项目角色。点底部保存后生效。</p>
          <div v-if="!editForm.memberships.length" class="hint">未派进项目</div>
          <div v-for="(m, i) in editForm.memberships" :key="m.projectId" class="mem-row">
            <span class="mem-name">{{ projectName(m.projectId) }}</span>
            <a-select v-model:value="m.role" :options="projectRoleOpts" style="width: 140px" />
            <a-button size="small" @click="removeMembership(i)">移出</a-button>
          </div>
          <div class="mem-add">
            <a-select
              v-model:value="editForm.addProjectId"
              :options="addableProjectOpts"
              placeholder="选择项目"
              allow-clear
              style="flex: 1"
            />
            <a-select v-model:value="editForm.addRole" :options="projectRoleOpts" style="width: 140px" />
            <a-button size="small" @click="addMembership">加入</a-button>
          </div>
        </div>

        <div class="drawer-sec">
          <h4>指定管理员</h4>
          <p class="lead">把该用户设为所选项目的管理员。立即生效，不必点保存。</p>
          <div class="mem-add">
            <a-select
              v-model:value="editForm.assignProjectId"
              :options="allProjectOpts"
              placeholder="选择项目"
              allow-clear
              style="flex: 1"
            />
            <a-button size="small" type="primary" :loading="busy" @click="submitAssignInDrawer">指定</a-button>
          </div>
        </div>

        <div class="drawer-sec">
          <h4>重置密码</h4>
          <p class="lead">单独提交，不会随底部保存一起改密码。</p>
          <div class="mem-add">
            <a-input-password v-model:value="editForm.newPassword" placeholder="新密码" style="flex: 1" />
            <a-button size="small" :loading="busy" @click="submitResetInDrawer">重置</a-button>
          </div>
        </div>
      </a-form>
      <template #footer>
        <div class="drawer-foot">
          <a-button @click="openEditUser = false">取消</a-button>
          <a-button type="primary" :loading="busy" @click="submitEdit">保存</a-button>
        </div>
      </template>
    </a-drawer>

    <a-modal v-model:open="openCreate" title="新建 / 拉入用户" ok-text="加入" :confirm-loading="busy" @ok="submitCreate">
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
        <a-form-item v-else-if="multi" label="已有用户名">
          <a-input v-model:value="createForm.username" placeholder="已在其它租户存在的账号" />
        </a-form-item>
        <a-form-item v-if="multi" label="租户角色">
          <a-select v-model:value="createForm.role" :options="tenantRoleOpts" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="openGrant"
      :title="editingGrant ? '编辑平台授权码' : '生成平台授权码'"
      :ok-text="editingGrant ? '保存' : '生成'"
      :confirm-loading="busy"
      @ok="submitGrant"
    >
      <p class="lead">平台用户凭此码进入本租户。须指定可进的项目及每个项目的角色。到期或停用后进入资格失效。</p>
      <a-form layout="vertical">
        <a-form-item v-if="editingGrant" label="授权码">
          <a-input :value="editingGrant.code" disabled />
        </a-form-item>
        <a-form-item label="有效期" required>
          <a-radio-group v-model:value="grantForm.permanent">
            <a-radio :value="true">永久（直到停用）</a-radio>
            <a-radio :value="false">指定到期时间</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="!grantForm.permanent" label="到期时间" required>
          <a-date-picker
            v-model:value="grantForm.expiresAt"
            show-time
            format="YYYY-MM-DD HH:mm"
            style="width: 100%"
            placeholder="选择日期和时间"
          />
        </a-form-item>
        <a-form-item label="功能范围">
          <a-checkbox v-model:checked="grantForm.allModules">本租户已开通的全部功能</a-checkbox>
          <a-checkbox-group
            v-if="!grantForm.allModules"
            v-model:value="grantForm.modules"
            :options="moduleOpts"
            class="scope-box"
          />
        </a-form-item>
        <a-form-item label="项目与角色" required>
          <a-checkbox v-model:checked="grantForm.allProjects">全部项目（同一角色）</a-checkbox>
          <div v-if="grantForm.allProjects" class="grant-role-row">
            <span>进入后的项目角色</span>
            <a-select v-model:value="grantForm.defaultRole" :options="projectRoleOpts" style="width: 180px" />
          </div>
          <div v-else class="grant-projects">
            <div v-for="p in grantProjectList" :key="p.id" class="grant-proj">
              <a-checkbox :checked="grantForm.projectIds.includes(p.id)" @change="() => toggleGrantProject(p.id)">
                {{ p.name }}
              </a-checkbox>
              <a-select
                v-if="grantForm.projectIds.includes(p.id)"
                :value="grantForm.projectRoles[p.id] || 'viewer'"
                :options="projectRoleOpts"
                style="width: 160px"
                @change="(v: unknown) => (grantForm.projectRoles[p.id] = v as ProjectRole)"
              />
            </div>
            <p v-if="!grantProjectList.length" class="hint">本组织还没有可指定的项目。</p>
          </div>
        </a-form-item>
        <a-form-item label="AI 能力">
          <p class="lead">不勾选表示不额外限制，沿用本组织已开通的 AI。勾选后只开这些项（仍须组织许可里有）。</p>
          <a-checkbox-group v-model:value="grantForm.aiCaps" :options="aiCapOpts" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="openTransfer" title="转让租户管理员" ok-text="转让" :confirm-loading="busy" @ok="submitTransfer">
      <p class="lead">转让后你降为普通成员，对方成为本租户管理员。平台用户持码不能转让。</p>
      <a-select v-model:value="transferTo" placeholder="选择本租户用户" :options="transferOpts" style="width: 100%" />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { message } from 'ant-design-vue';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import { api, type Grant, type OrgUser } from '../api/client';
import PageHeader from '../components/PageHeader.vue';
import {
  AI_CAP_OPTIONS,
  MODULE_OPTIONS,
  PROJECT_ROLE_LABEL,
  TENANT_ROLE_LABEL,
  aiCapLabel,
  type AiCap,
  type ProjectRole,
} from '../config/iam';
import { isMultiTenant } from '../config/runtime';
import { app, isRealTenantAdmin, isTenantAdmin, refreshSession, sessionAccount } from '../stores/app';

const multi = isMultiTenant();
const tid = computed(() => app.currentTenantId);
const rows = ref<OrgUser[]>([]);
const grants = ref<Grant[]>([]);
const openCreate = ref(false);
const openEditUser = ref(false);
const editing = ref<OrgUser | null>(null);
const editingGrant = ref<Grant | null>(null);
const openGrant = ref(false);
const openTransfer = ref(false);
const transferTo = ref<string>();
const busy = ref(false);

const createForm = reactive({
  mode: 'new' as 'new' | 'existing',
  username: '',
  displayName: '',
  password: '123456',
  existingUserId: '',
  role: 'member',
});

const editForm = reactive({
  displayName: '',
  username: '',
  tenantRole: 'member',
  status: 'active',
  memberships: [] as { projectId: string; role: ProjectRole }[],
  addProjectId: undefined as string | undefined,
  addRole: 'modeler' as ProjectRole,
  assignProjectId: undefined as string | undefined,
  newPassword: '123456',
});

const grantForm = reactive({
  permanent: false,
  expiresAt: dayjs().add(7, 'day').hour(18).minute(0).second(0) as Dayjs | null,
  allModules: true,
  modules: [] as string[],
  allProjects: false,
  projectIds: [] as string[],
  defaultRole: 'viewer' as ProjectRole,
  projectRoles: {} as Record<string, ProjectRole>,
  aiCaps: [] as AiCap[],
});

const aiCapOpts = AI_CAP_OPTIONS.map((c) => ({ value: c.value, label: `${c.label}（${c.hint}）` }));

const tenantRoleOpts = (Object.keys(TENANT_ROLE_LABEL) as ('admin' | 'member')[]).map((v) => ({
  value: v,
  label: v === 'admin' && multi ? '租户管理员' : TENANT_ROLE_LABEL[v],
}));

const projectRoleOpts = (Object.keys(PROJECT_ROLE_LABEL) as ProjectRole[]).map((v) => ({
  value: v,
  label: PROJECT_ROLE_LABEL[v],
}));

const tenantProjectList = computed(() => app.projects.filter((p) => p.tenantId === app.currentTenantId));
const grantProjectList = computed(() =>
  tenantProjectList.value.filter((p) => (p.status ?? 'active') !== 'disabled')
);

const projectOpts = computed(() =>
  tenantProjectList.value
    .filter((p) => (p.status ?? 'active') !== 'disabled')
    .map((p) => ({ value: p.id, label: p.name }))
);

const allProjectOpts = computed(() => tenantProjectList.value.map((p) => ({ value: p.id, label: p.name })));

const addableProjectOpts = computed(() => {
  const taken = new Set(editForm.memberships.map((m) => m.projectId));
  return tenantProjectList.value
    .filter((p) => !taken.has(p.id))
    .map((p) => ({ value: p.id, label: p.name }));
});

const cols = computed(() => {
  const base: { title: string; dataIndex?: string; key?: string; width?: number }[] = [
    { title: '显示名', dataIndex: 'displayName' },
    { title: '用户名', dataIndex: 'username' },
  ];
  if (multi) base.push({ title: '租户角色', key: 'role', width: 120 });
  base.push(
    { title: '状态', key: 'status', width: 80 },
    { title: '项目', key: 'projects' },
    { title: '操作', key: 'act', width: 140 }
  );
  return base;
});

const moduleOpts = computed(() => {
  const lic = app.licenses.find((l) => l.tenantId === app.currentTenantId);
  const opened = lic?.modules?.length ? lic.modules : MODULE_OPTIONS.map((m) => m.value);
  return MODULE_OPTIONS.filter((m) => opened.includes(m.value));
});

const grantCols = [
  { title: '授权码', dataIndex: 'code' },
  { title: '种类', key: 'kind', width: 90 },
  { title: '到期', key: 'expires', width: 160 },
  { title: '范围', key: 'scope' },
  { title: 'AI 能力', key: 'aiCaps', width: 180 },
  { title: '状态', key: 'ok', width: 80 },
  { title: '操作', key: 'act', width: 200 },
];

const transferOpts = computed(() =>
  rows.value
    .filter((u) => u.id !== sessionAccount.value?.id && u.status === 'active')
    .map((u) => ({ value: u.id, label: `${u.displayName}（${u.username}）` }))
);

function kindLabel(kind: string) {
  if (kind === 'permanent') return '永久';
  if (kind === 'until') return '指定到期';
  if (kind === '1h') return '1 小时';
  if (kind === '1d') return '1 天';
  if (kind === '7d') return '7 天';
  return kind;
}

function formatWhen(raw: string) {
  const d = dayjs(raw);
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm') : raw;
}

function scopeLine(g: Grant) {
  const mods = g.modules ?? [];
  const pids = g.projectIds ?? [];
  const featLabels = mods
    .map((m) => MODULE_OPTIONS.find((x) => x.value === m)?.label)
    .filter((x): x is string => Boolean(x));
  const feat = mods.length ? featLabels.join('、') || '全部功能' : '全部功能';
  const roles = new Map((g.projectScopes ?? []).map((s) => [s.projectId, s.role]));
  const proj = pids.length
    ? pids
        .map((id) => {
          const name = app.projects.find((p) => p.id === id)?.name ?? id;
          const role = roles.get(id) || g.defaultRole || 'viewer';
          return `${name}（${PROJECT_ROLE_LABEL[role as ProjectRole] ?? role}）`;
        })
        .join('、')
    : `全部项目（${PROJECT_ROLE_LABEL[(g.defaultRole || 'viewer') as ProjectRole] ?? g.defaultRole}）`;
  return `${feat} · ${proj}`;
}

function toggleGrantProject(id: string) {
  const i = grantForm.projectIds.indexOf(id);
  if (i >= 0) {
    grantForm.projectIds.splice(i, 1);
    delete grantForm.projectRoles[id];
    return;
  }
  grantForm.projectIds.push(id);
  if (!grantForm.projectRoles[id]) grantForm.projectRoles[id] = 'viewer';
}

function projectName(projectId: string) {
  return tenantProjectList.value.find((p) => p.id === projectId)?.name ?? projectId;
}

function projectLine(userId: string) {
  const parts = tenantProjectList.value
    .filter((p) => app.members.some((m) => m.projectId === p.id && m.userId === userId))
    .map((p) => {
      const role = app.members.find((m) => m.projectId === p.id && m.userId === userId)?.role;
      return `${p.name}（${role ? PROJECT_ROLE_LABEL[role] : ''}）`;
    });
  return parts.join('、') || '未派进项目';
}

async function reload() {
  if (!tid.value) return;
  rows.value = await api.org.users(tid.value);
  if (multi && isRealTenantAdmin.value) {
    try {
      grants.value = await api.org.grants(tid.value);
    } catch {
      grants.value = [];
    }
  }
}

async function submitCreate() {
  if (!tid.value) return;
  busy.value = true;
  try {
    await api.org.createUser(tid.value, {
      username: createForm.username,
      displayName: createForm.mode === 'new' ? createForm.displayName : undefined,
      password: createForm.mode === 'new' ? createForm.password : undefined,
      tenantRole: createForm.role,
    });
    openCreate.value = false;
    createForm.username = '';
    createForm.displayName = '';
    await reload();
    message.success('已加入本组织');
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

function openEdit(u: OrgUser) {
  editing.value = u;
  editForm.displayName = u.displayName;
  editForm.username = u.username;
  editForm.tenantRole = u.tenantRole || 'member';
  editForm.status = u.status === 'disabled' ? 'disabled' : 'active';
  editForm.memberships = tenantProjectList.value
    .filter((p) => app.members.some((m) => m.projectId === p.id && m.userId === u.id))
    .map((p) => ({
      projectId: p.id,
      role: (app.members.find((m) => m.projectId === p.id && m.userId === u.id)?.role ?? 'viewer') as ProjectRole,
    }));
  editForm.addProjectId = undefined;
  editForm.addRole = 'modeler';
  editForm.assignProjectId = allProjectOpts.value[0]?.value;
  editForm.newPassword = '123456';
  openEditUser.value = true;
}

function addMembership() {
  if (!editForm.addProjectId) {
    message.warning('请选择要加入的项目');
    return;
  }
  if (editForm.memberships.some((m) => m.projectId === editForm.addProjectId)) return;
  editForm.memberships.push({ projectId: editForm.addProjectId, role: editForm.addRole });
  editForm.addProjectId = undefined;
}

function removeMembership(index: number) {
  editForm.memberships.splice(index, 1);
}

async function submitEdit() {
  if (!editing.value || !tid.value) return;
  const name = editForm.displayName.trim();
  const username = editForm.username.trim();
  if (!name) {
    message.warning('请填写显示名');
    return;
  }
  if (!username) {
    message.warning('请填写用户名');
    return;
  }
  busy.value = true;
  try {
    await api.org.patchUser(tid.value, editing.value.id, {
      displayName: name,
      username,
      tenantRole: editForm.tenantRole,
      status: editForm.status,
      memberships: editForm.memberships.map((m) => ({ projectId: m.projectId, role: m.role })),
    });
    openEditUser.value = false;
    await refreshSession();
    await reload();
    message.success('已保存');
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

async function submitAssignInDrawer() {
  if (!editing.value || !editForm.assignProjectId || !tid.value) return;
  busy.value = true;
  try {
    await api.org.patchUser(tid.value, editing.value.id, { projectId: editForm.assignProjectId });
    const exist = editForm.memberships.find((m) => m.projectId === editForm.assignProjectId);
    if (exist) exist.role = 'admin';
    else editForm.memberships.push({ projectId: editForm.assignProjectId, role: 'admin' });
    await refreshSession();
    await reload();
    message.success('已指定项目管理员');
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

async function submitResetInDrawer() {
  if (!editing.value || !tid.value) return;
  if (!editForm.newPassword) {
    message.warning('请填写新密码');
    return;
  }
  busy.value = true;
  try {
    await api.org.patchUser(tid.value, editing.value.id, { password: editForm.newPassword });
    message.success('密码已重置');
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

function resetGrantForm() {
  grantForm.permanent = false;
  grantForm.expiresAt = dayjs().add(7, 'day').hour(18).minute(0).second(0);
  grantForm.allModules = true;
  grantForm.modules = [];
  grantForm.allProjects = false;
  grantForm.projectIds = [];
  grantForm.defaultRole = 'viewer';
  grantForm.projectRoles = {};
  grantForm.aiCaps = [];
}

function startCreateGrant() {
  editingGrant.value = null;
  resetGrantForm();
  openGrant.value = true;
}

function startEditGrant(g: Grant) {
  editingGrant.value = g;
  grantForm.permanent = g.kind === 'permanent' || !g.expiresAt;
  grantForm.expiresAt = g.expiresAt ? dayjs(g.expiresAt) : dayjs().add(7, 'day').hour(18).minute(0).second(0);
  const mods = g.modules ?? [];
  grantForm.allModules = !mods.length;
  grantForm.modules = [...mods];
  const pids = g.projectIds ?? [];
  grantForm.allProjects = !pids.length;
  grantForm.projectIds = [...pids];
  grantForm.defaultRole = ((g.defaultRole as ProjectRole) || 'viewer') as ProjectRole;
  const nextRoles: Record<string, ProjectRole> = {};
  for (const s of g.projectScopes ?? []) {
    if (s.projectId && (s.role === 'admin' || s.role === 'modeler' || s.role === 'viewer')) {
      nextRoles[s.projectId] = s.role;
    }
  }
  grantForm.projectRoles = nextRoles;
  grantForm.aiCaps = [...((g.aiCaps ?? []) as AiCap[])];
  openGrant.value = true;
}

async function submitGrant() {
  if (!tid.value) return;
  if (!grantForm.permanent && !grantForm.expiresAt) {
    message.warning('请选择到期时间');
    return;
  }
  if (!grantForm.allModules && !grantForm.modules.length) {
    message.warning('请勾选至少一项功能，或选择全部功能');
    return;
  }
  if (!grantForm.allProjects && !grantForm.projectIds.length) {
    message.warning('请勾选至少一个项目，或选择全部项目');
    return;
  }
  busy.value = true;
  try {
    const body = {
      permanent: grantForm.permanent,
      expiresAt: grantForm.permanent ? undefined : grantForm.expiresAt?.toISOString(),
      modules: grantForm.allModules ? [] : grantForm.modules,
      projectIds: grantForm.allProjects ? [] : grantForm.projectIds,
      defaultRole: grantForm.defaultRole,
      projectScopes: grantForm.allProjects
        ? []
        : grantForm.projectIds.map((id) => ({
            projectId: id,
            role: grantForm.projectRoles[id] || grantForm.defaultRole || 'viewer',
          })),
      aiCaps: grantForm.aiCaps,
    };
    if (editingGrant.value) {
      await api.org.patchGrant(tid.value, editingGrant.value.id, body);
      openGrant.value = false;
      editingGrant.value = null;
      await reload();
      message.success('已保存授权码');
    } else {
      const created = await api.org.createGrant(tid.value, body);
      openGrant.value = false;
      await reload();
      message.success(`已生成授权码 ${created.code}`);
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

async function revoke(id: string) {
  if (!tid.value) return;
  try {
    await api.org.revokeGrant(tid.value, id);
    await reload();
    message.success('已停用');
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  }
}

async function removeGrant(id: string) {
  if (!tid.value) return;
  try {
    await api.org.deleteGrant(tid.value, id);
    await reload();
    message.success('已删除授权码');
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  }
}

async function removeUser(u: OrgUser) {
  if (!tid.value) return;
  try {
    await api.org.deleteUser(tid.value, u.id);
    await refreshSession();
    await reload();
    message.success('已从本组织移除');
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e));
  }
}

async function submitTransfer() {
  if (!transferTo.value || !tid.value) return;
  busy.value = true;
  try {
    await api.org.transferAdmin(tid.value, transferTo.value);
    openTransfer.value = false;
    message.success('已转让');
    await reload();
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

.scope-box {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}

.grant-role-row,
.grant-proj {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
}

.grant-projects {
  margin-top: 8px;
}

.grant-role-row span,
.hint {
  color: var(--muted);
  font-size: 13px;
}
</style>

<style>
.user-edit-drawer .drawer-sec {
  margin-bottom: 22px;
}

.user-edit-drawer .drawer-sec h4 {
  margin: 0 0 8px;
  font-size: 14px;
}

.user-edit-drawer .lead {
  color: #64748b;
  font-size: 13px;
  margin: 0 0 12px;
}

.user-edit-drawer .hint {
  color: #94a3b8;
  font-size: 12px;
  margin-top: 6px;
}

.user-edit-drawer .mem-row,
.user-edit-drawer .mem-add {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.user-edit-drawer .mem-name {
  flex: 1;
  min-width: 0;
}

.user-edit-drawer .drawer-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
