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
          {{ nameOf(record.userId) }}
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
  { title: '用户', dataIndex: 'userId' },
  { title: '角色', key: 'role', width: 200 },
];

function nameOf(userId: string) {
  return orgUsers.value.find((u) => u.id === userId)?.displayName || userId;
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
