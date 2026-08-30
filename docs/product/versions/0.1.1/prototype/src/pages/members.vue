<template>
  <div class="page">
    <PageHeader title="项目成员" subtitle="项目管理员从用户里拉人，并给每人指定角色。开账号请租户管理员到工作台「用户管理」。">
      <template #actions>
        <a-button v-if="canAdd" type="primary" @click="open = true">添加成员</a-button>
      </template>
    </PageHeader>
    <a-table :data-source="rows" :columns="cols" row-key="userId" size="small" :pagination="false" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'role'">
          <a-select
            v-if="canAdd"
            :value="record.role"
            :options="roleOpts"
            style="width: 160px"
            @change="(v: unknown) => setMemberRole(record.userId, v as ProjectRole)"
          />
          <span v-else>{{ PROJECT_ROLE_LABEL[record.role as ProjectRole] }}</span>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="open" title="添加成员" ok-text="加入" :ok-button-props="{ disabled: !pick }" @ok="add">
      <a-form layout="vertical">
        <a-form-item label="本租户用户">
          <a-select
            v-model:value="pick"
            placeholder="选择本租户用户"
            :options="available.map((u) => ({ value: u.id, label: `${u.displayName}（${u.username}）` }))"
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
import { computed, ref } from 'vue';
import { message } from 'ant-design-vue';
import PageHeader from '../components/PageHeader.vue';
import { PROJECT_ROLE_LABEL } from '../config/iam';
import type { ProjectRole } from '../types';
import { isProjectAdmin, projectMemberList, setMemberRole, tenantUsers } from '../stores/app';

const rows = projectMemberList;
const open = ref(false);
const pick = ref<string>();
const role = ref<ProjectRole>('viewer');
const roleOpts = (Object.keys(PROJECT_ROLE_LABEL) as ProjectRole[]).map((v) => ({
  value: v,
  label: PROJECT_ROLE_LABEL[v],
}));

const canAdd = computed(() => isProjectAdmin());
const available = computed(() =>
  tenantUsers().filter((u) => u.status === 'active' && !rows.value.some((m) => m.userId === u.id))
);

const cols = [
  { title: '用户', dataIndex: 'displayName' },
  { title: '用户名', dataIndex: 'username' },
  { title: '角色', key: 'role', width: 200 },
];

function add() {
  if (!pick.value) {
    message.warning('请选择本租户用户');
    return;
  }
  setMemberRole(pick.value, role.value);
  pick.value = undefined;
  role.value = 'viewer';
  open.value = false;
}
</script>
