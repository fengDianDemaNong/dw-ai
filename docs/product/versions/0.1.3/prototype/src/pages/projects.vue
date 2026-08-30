<template>
  <div class="page">
    <PageHeader
      :title="multi ? '项目管理' : '项目'"
      :subtitle="
        multi
          ? '租户管理员新建项目并指定项目管理员；项目管理员再进项目拉人、派角色。未加入的项目不会出现在普通成员列表里。'
          : '管理员新建项目并指定项目管理员。普通模式没有租户和平台后台。'
      "
    >
      <template #actions>
        <a-radio-group v-model:value="view" size="small" button-style="solid">
          <a-radio-button value="card">块</a-radio-button>
          <a-radio-button value="row">行</a-radio-button>
        </a-radio-group>
        <a-button v-if="isTenantAdmin" type="primary" @click="openCreate">新增</a-button>
      </template>
    </PageHeader>

    <div v-if="view === 'card'" class="grid projects-grid">
      <div v-for="p in tenantProjects" :key="p.id" class="card proj" :class="{ off: isDisabled(p) }">
        <div class="row">
          <h3>{{ p.name }}</h3>
          <a-space>
            <a-tag>{{ p.code }}</a-tag>
            <a-tag :color="isDisabled(p) ? 'default' : 'green'">{{ isDisabled(p) ? '已停用' : '使用中' }}</a-tag>
          </a-space>
        </div>
        <p>{{ p.description || '暂无描述' }}</p>
        <div class="meta">项目管理员 {{ ownerName(p.owner) }} · {{ p.createdAt }}</div>
        <div class="kb">
          <a-tag v-for="e in p.engines ?? []" :key="e">{{ engineLabel(e) }}</a-tag>
          <span v-if="!(p.engines ?? []).length" class="muted inline">未开通知识库</span>
        </div>
        <div class="ops">
          <a-button v-if="!isDisabled(p)" type="primary" @click="go(p.id)">进入项目</a-button>
          <a-button v-if="isTenantAdmin" @click="openEdit(p)">编辑</a-button>
        </div>
      </div>
    </div>
    <a-table
      v-else
      :data-source="tenantProjects"
      :columns="rowCols"
      row-key="id"
      :pagination="false"
      size="small"
      class="card projects-table"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'owner'">{{ ownerName(record.owner) }}</template>
        <template v-else-if="column.key === 'engines'">
          <a-tag v-for="e in record.engines ?? []" :key="e">{{ engineLabel(e) }}</a-tag>
          <span v-if="!(record.engines ?? []).length" class="muted">—</span>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="isDisabled(record) ? 'default' : 'green'">{{ isDisabled(record) ? '已停用' : '使用中' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'act'">
          <a-space>
            <a-button v-if="!isDisabled(record)" type="link" size="small" @click="go(record.id)">进入项目</a-button>
            <a-button v-if="isTenantAdmin" type="link" size="small" @click="openEdit(record)">编辑</a-button>
          </a-space>
        </template>
      </template>
    </a-table>
    <p v-if="!tenantProjects.length" class="muted">还没有可进入的项目。</p>

    <a-drawer
      v-model:open="editOpen"
      title="编辑项目"
      placement="right"
      width="480"
      :destroy-on-close="true"
      root-class-name="project-edit-drawer"
    >
      <a-form layout="vertical">
        <div class="drawer-sec">
          <h4>基本信息</h4>
          <a-form-item label="项目名称" required>
            <a-input v-model:value="editForm.name" />
          </a-form-item>
          <a-form-item label="项目编码">
            <a-input v-model:value="editForm.code" />
          </a-form-item>
          <a-form-item label="说明">
            <a-textarea v-model:value="editForm.description" :rows="3" />
          </a-form-item>
          <a-form-item label="项目管理员" required>
            <a-select v-model:value="editForm.adminUserId" :options="adminOpts" />
          </a-form-item>
          <a-form-item label="状态">
            <a-radio-group v-model:value="editForm.status">
              <a-radio value="active">启用</a-radio>
              <a-radio value="disabled">停用</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item label="知识库">
            <a-checkbox-group v-model:value="editForm.engines" :options="engineOpts" />
            <div class="hint">勾选后，项目内「知识库」只显示这些引擎的手册（含本租户导入篇）。不勾选则项目知识库为空。</div>
          </a-form-item>
        </div>
        <div class="drawer-sec">
          <h4>创建信息</h4>
          <p class="lead">{{ editing?.createdAt || '—' }} · 当前编码 {{ editing?.code || '—' }}</p>
        </div>
        <a-button v-if="editing && !isDisabled({ status: editForm.status })" type="primary" block @click="goFromDrawer">
          进入项目
        </a-button>
        <a-popconfirm
          v-if="isTenantAdmin && editing"
          title="删除项目将同时删除其中的规范与模型，不可恢复。"
          @confirm="removeFromDrawer"
        >
          <a-button danger block class="drawer-del">删除项目</a-button>
        </a-popconfirm>
      </a-form>
      <template #footer>
        <div class="drawer-foot">
          <a-button @click="editOpen = false">取消</a-button>
          <a-button type="primary" @click="submitEdit">保存</a-button>
        </div>
      </template>
    </a-drawer>

    <a-modal v-model:open="open" title="新建项目" ok-text="创建" @ok="create">
      <a-form layout="vertical">
        <a-form-item label="项目编码" required>
          <a-input v-model:value="form.code" placeholder="trade_dw" />
        </a-form-item>
        <a-form-item label="项目名称" required>
          <a-input v-model:value="form.name" placeholder="交易数仓" />
        </a-form-item>
        <a-form-item label="描述">
          <a-textarea v-model:value="form.description" :rows="3" />
        </a-form-item>
        <a-form-item label="项目管理员" required>
          <a-select
            v-model:value="form.adminUserId"
            placeholder="从本组织用户中指定"
            :options="adminOpts"
          />
        </a-form-item>
        <a-form-item label="知识库">
          <a-checkbox-group v-model:value="form.engines" :options="engineOpts" />
          <div class="hint">可先不勾，之后在编辑或工作台「知识库」里再开。</div>
        </a-form-item>
        <a-form-item>
          <a-checkbox v-model:checked="form.bootstrapSpec">
            导入通用规范模板（分层 + 四级等级 + 基础词根）
          </a-checkbox>
          <div class="hint">空项目建议勾选，之后可在规范中心再改。不勾选则只有技术/时间词根。</div>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import PageHeader from '../components/PageHeader.vue';
import { ENGINE_OPTIONS, engineLabel } from '../config/knowledge';
import { isMultiTenant } from '../config/runtime';
import type { EngineKind, Project } from '../types';
import {
  app,
  createProject,
  enterProject,
  isTenantAdmin,
  patchProject,
  removeProject,
  sessionAccount,
  tenantProjects,
  tenantUsers,
} from '../stores/app';

const VIEW_KEY = 'dw-ai.proto.0.1.3.projectView';
const router = useRouter();
const multi = isMultiTenant();
const view = ref<'card' | 'row'>((sessionStorage.getItem(VIEW_KEY) as 'card' | 'row') || 'card');
const open = ref(false);
const editOpen = ref(false);
const editing = ref<Project | null>(null);
const engineOpts = ENGINE_OPTIONS.map((e) => ({ value: e.value, label: e.label }));
const form = reactive({
  code: '',
  name: '',
  description: '',
  adminUserId: sessionAccount.value?.id ?? '',
  bootstrapSpec: true,
  engines: [] as EngineKind[],
});
const editForm = reactive({
  name: '',
  code: '',
  description: '',
  adminUserId: '',
  status: 'active' as 'active' | 'disabled',
  engines: [] as EngineKind[],
});
const adminOpts = computed(() =>
  tenantUsers()
    .filter((u) => u.status === 'active')
    .map((u) => ({ value: u.id, label: `${u.displayName}（${u.username}）` }))
);

const rowCols = [
  { title: '项目', dataIndex: 'name' },
  { title: '编码', dataIndex: 'code', width: 140 },
  { title: '描述', dataIndex: 'description' },
  { title: '项目管理员', key: 'owner', width: 140 },
  { title: '知识库', key: 'engines', width: 200 },
  { title: '状态', key: 'status', width: 90 },
  { title: '创建', dataIndex: 'createdAt', width: 120 },
  { title: '操作', key: 'act', width: 160 },
];

watch(view, (v) => sessionStorage.setItem(VIEW_KEY, v));

function ownerName(owner: string) {
  return app.accounts.find((a) => a.id === owner || a.displayName === owner)?.displayName || owner;
}

function ownerId(owner: string) {
  return app.accounts.find((a) => a.id === owner || a.displayName === owner)?.id ?? '';
}

function isDisabled(p: { status?: string }) {
  return (p.status ?? 'active') === 'disabled';
}

function openCreate() {
  form.adminUserId = sessionAccount.value?.id ?? adminOpts.value[0]?.value ?? '';
  open.value = true;
}

function openEdit(p: Project) {
  editing.value = p;
  editForm.name = p.name;
  editForm.code = p.code;
  editForm.description = p.description || '';
  editForm.adminUserId = ownerId(p.owner);
  editForm.status = isDisabled(p) ? 'disabled' : 'active';
  editForm.engines = [...(p.engines ?? [])];
  editOpen.value = true;
}

function submitEdit() {
  if (!editing.value) return;
  if (
    !patchProject(editing.value.id, {
      name: editForm.name,
      code: editForm.code,
      description: editForm.description,
      adminUserId: editForm.adminUserId,
      status: editForm.status,
      engines: editForm.engines,
    })
  ) {
    return;
  }
  editOpen.value = false;
}

function go(id: string) {
  if (!enterProject(id)) return;
  router.push('/w');
}

function goFromDrawer() {
  if (!editing.value) return;
  const id = editing.value.id;
  editOpen.value = false;
  go(id);
}

function removeFromDrawer() {
  if (!editing.value) return;
  if (!removeProject(editing.value.id)) return;
  editOpen.value = false;
}

function create() {
  if (!form.code || !form.name) {
    message.warning('请填写编码和名称');
    return;
  }
  const p = createProject({
    code: form.code,
    name: form.name,
    description: form.description,
    adminUserId: form.adminUserId,
    bootstrapSpec: form.bootstrapSpec,
    engines: form.engines,
  });
  if (!p) return;
  open.value = false;
  form.code = '';
  form.name = '';
  form.description = '';
  form.bootstrapSpec = true;
  form.engines = [];
}
</script>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.proj h3 {
  margin: 0;
  font-size: 16px;
}

.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.proj p {
  min-height: 40px;
  margin: 8px 0 12px;
  color: var(--muted);
  font-size: 13px;
}

.ops {
  display: flex;
  gap: 8px;
}

.ops :deep(.ant-btn) {
  flex: 1;
}

.off {
  opacity: 0.72;
}

.kb {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin: -4px 0 10px;
  min-height: 24px;
}

.inline {
  margin: 0;
}

.meta,
.hint,
.muted {
  margin-bottom: 12px;
  color: var(--muted);
  font-size: 13px;
}

.hint {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
}
</style>

<style>
.project-edit-drawer .drawer-sec {
  margin-bottom: 22px;
}

.project-edit-drawer .drawer-sec h4 {
  margin: 0 0 8px;
  font-size: 14px;
}

.project-edit-drawer .lead {
  color: #64748b;
  font-size: 13px;
  margin: 0;
}

.project-edit-drawer .drawer-del {
  margin-top: 10px;
}

.project-edit-drawer .drawer-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
