<template>
  <div class="page">
    <PageHeader title="项目成员" subtitle="角色在本项目生效。同一人在其它项目可以是不同角色。管理员从本组织用户拉人。">
      <template #actions>
        <a-tag>{{ currentRoleLabel }}</a-tag>
        <a-button v-if="canAdd" type="primary" @click="open = true">添加成员</a-button>
      </template>
    </PageHeader>
    <a-table :data-source="rows" :columns="cols" row-key="userId" size="small" :pagination="false" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          {{ nameOf(record) }}
        </template>
        <template v-else-if="column.key === 'account'">
          {{ accountOf(record) }}
        </template>
        <template v-else-if="column.key === 'role'">
          <a-select
            :value="record.role"
            :options="roleOpts"
            style="width: 160px"
            :disabled="!can('iam:member')"
            @change="(v: unknown) => setMemberRole(record.userId, v as ProjectRole)"
          />
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="open" title="添加成员" ok-text="加入" :ok-button-props="{ disabled: !pick }" @ok="add">
      <a-form layout="vertical">
        <a-form-item label="用户">
          <a-select
            v-model:value="pick"
            placeholder="从本组织用户中选择"
            :options="availableUsers"
          />
        </a-form-item>
        <a-form-item label="角色">
          <a-select v-model:value="role" :options="roleOpts" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { message } from 'ant-design-vue';
import { api, type OrgUser } from '../api/client';
import PageHeader from '../components/PageHeader.vue';
import { ROLE_LABEL, type ProjectRole } from '../config/iam';
import { app, can, currentRoleLabel, projectMemberList, setMemberRole } from '../stores/app';

const rows = projectMemberList;
const orgUsers = ref<OrgUser[]>([]);
const open = ref(false);
const pick = ref<string>();
const role = ref<ProjectRole>('viewer');
const roleOpts = (Object.keys(ROLE_LABEL) as ProjectRole[]).map((v) => ({
  value: v,
  label: ROLE_LABEL[v],
}));
const availableUsers = computed(() =>
  orgUsers.value
    .filter((u) => u.status === 'active' && !rows.value.some((m) => m.userId === u.id))
    .map((u) => ({ value: u.id, label: `${u.displayName}（${u.username}）` }))
);
const canAdd = computed(() => can('iam:member') && availableUsers.value.length > 0);
const cols = [
  { title: '显示名', key: 'name' },
  { title: '用户', key: 'account' },
  { title: '角色', key: 'role', width: 200 },
];

/**
 * 显示名。
 *
 * <p>优先用后端直接给的（仓建设 multi 下由组织推来，见 `GET /internal/v1/members`）；
 * 再退到本地拉的组织用户列表（`api.org.users` 在仓建设后端并不存在，那一列一直是空的，
 * 留着是为了本地模式）；最后才退回 `userId`。
 */
function nameOf(m: { userId: string; displayName?: string | null }) {
  if (m.displayName) return m.displayName;
  return orgUsers.value.find((u) => u.id === m.userId)?.displayName || m.userId;
}

/**
 * 登录账号。
 *
 * <p>这一列以前直接渲染 `userId`，于是页面上出现的是 `u-1790075246594` 这样的内部主键 ——
 * 对人没有任何意义。换成登录名；确实拿不到时退回 `userId`，总比空着让人以为这行坏了强。
 */
function accountOf(m: { userId: string; username?: string | null }) {
  return m.username || m.userId;
}

function add() {
  if (!pick.value) {
    message.warning('请选择用户');
    return;
  }
  setMemberRole(pick.value, role.value);
  pick.value = undefined;
  role.value = 'viewer';
  open.value = false;
}

onMounted(async () => {
  if (app.currentTenantId) {
    try {
      orgUsers.value = await api.org.users(app.currentTenantId);
    } catch {
      orgUsers.value = [];
    }
  }
});
</script>
