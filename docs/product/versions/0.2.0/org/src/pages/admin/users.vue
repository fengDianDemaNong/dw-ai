<template>
  <div class="page">
    <PageHeader title="平台用户" subtitle="这些账号只管理平台：开通租户、改模块、管理其它平台用户。进租户仍要授权码。">
      <template #actions>
        <a-button type="primary" @click="openStaff = true">新增平台用户</a-button>
      </template>
    </PageHeader>

    <a-table :data-source="staff" :columns="staffCols" row-key="id" :pagination="false" size="small" class="card card-flush">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'">
          <a-tag :color="record.status === 'active' ? 'green' : 'default'">
            {{ record.status === 'active' ? '正常' : '停用' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'act'">
          <a-space>
            <a-button size="small" @click="openPwd(record)">改密码</a-button>
            <a-button size="small" :disabled="record.id === sessionAccount?.id" @click="toggleStaff(record)">
              {{ record.status === 'active' ? '停用' : '启用' }}
            </a-button>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="openStaff" title="新增平台用户" ok-text="创建" @ok="submitStaff">
      <a-form layout="vertical">
        <a-form-item label="用户名" required>
          <a-input v-model:value="staffForm.username" />
        </a-form-item>
        <a-form-item label="显示名">
          <a-input v-model:value="staffForm.displayName" />
        </a-form-item>
        <a-form-item label="初始密码" required>
          <a-input-password v-model:value="staffForm.password" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="openPass" title="修改密码" ok-text="保存" @ok="submitPass">
      <a-form layout="vertical">
        <a-form-item :label="pwdTarget ? `新密码 · ${pwdTarget.displayName}` : '新密码'">
          <a-input-password v-model:value="newPass" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import PageHeader from '../../components/PageHeader.vue';
import type { Account } from '../../types';
import { createPlatformUser, platformUsers, sessionAccount, setPlatformPassword, setPlatformStatus } from '../../stores/app';

const openStaff = ref(false);
const openPass = ref(false);
const pwdTarget = ref<Account | null>(null);
const newPass = ref('ops123');
const staffForm = reactive({
  username: '',
  displayName: '',
  password: 'ops123',
});

const staff = computed(() => platformUsers());
const staffCols = [
  { title: '显示名', dataIndex: 'displayName' },
  { title: '用户名', dataIndex: 'username' },
  { title: '状态', key: 'status', width: 80 },
  { title: '操作', key: 'act', width: 180 },
];

function submitStaff() {
  const acc = createPlatformUser(staffForm);
  if (!acc) return;
  openStaff.value = false;
  staffForm.username = '';
  staffForm.displayName = '';
}

function openPwd(a: Account) {
  pwdTarget.value = a;
  newPass.value = '';
  openPass.value = true;
}

function submitPass() {
  if (!pwdTarget.value) return;
  setPlatformPassword(pwdTarget.value.id, newPass.value);
  openPass.value = false;
}

function toggleStaff(a: Account) {
  setPlatformStatus(a.id, a.status === 'active' ? 'disabled' : 'active');
}
</script>
